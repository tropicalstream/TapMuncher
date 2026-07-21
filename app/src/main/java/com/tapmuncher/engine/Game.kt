package com.tapmuncher.engine

import com.tapmuncher.SettingsStore
import com.tapmuncher.audio.Sfx
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.random.Random

enum class GameState { TITLE, READY, PLAY, DYING, CLEAR, PAUSED, GAME_OVER }

interface GameHost {
    fun sfx(id: Int, pitch: Float = 1f, vol: Float = 1f)
    fun sirenStart()
    fun sirenStop()
    fun sirenRate(rate: Float)
    fun frightStart()
    fun frightStop()
}

/** A maze denizen. type: 0 shadow, 1 speedy, 2 bashful, 3 pokey. */
class Ghost(val type: Int) {
    var x = 0f; var y = 0f
    var dir = 0
    var inHouse = true
    var exiting = false
    var eyes = false
    var exitAt = 0f          // game-time when it leaves the house
    val hue: Float get() = GHOST_HUES[type]
}

val GHOST_HUES = floatArrayOf(0.0f, 0.9f, 0.5f, 0.08f)   // red, pink, cyan, orange

/**
 * TapMuncher — a neon-vector maze-muncher remix. Classic bones: pellets,
 * four power pills, four pursuers with distinct brains, scatter/chase waves,
 * fruit, a wrap tunnel — drawn as glowing lines on an open 3D table.
 * Basic options live on the intro screen: difficulty, maze, lives.
 *
 * Hand-authored mazes are SELF-HEALED at load: unreachable corridors are
 * sealed and terminal branches are connected back into loops (or pruned when
 * no safe connection exists), so there are no visible/player-reachable dead
 * ends and an authoring slip can never strand a pellet or stop the player.
 */
class Game(private val store: SettingsStore, private val host: GameHost) {

    companion object {
        const val W = 25
        const val H = 17
        val DX = intArrayOf(0, 0, -1, 1)   // 0 up, 1 down, 2 left, 3 right
        val DY = intArrayOf(-1, 1, 0, 0)
        val WAVES = floatArrayOf(7f, 20f, 7f, 20f, 5f, 20f, 5f, 1e9f)
        val FRIGHT_SECS = floatArrayOf(14f, 10f, 6f)
        val DIFF_NAMES = arrayOf("EASY", "NORMAL", "HARD")
        val MAZE_NAMES = arrayOf("NEON", "OPEN")
        const val DOOR_X = 12; const val DOOR_Y = 6
        const val FRUIT_X = 15; const val FRUIT_Y = 9
        private const val MOVE_SUBSTEP = 0.055f
        private const val LEVEL_READY_SECS = 2.9f

        val MAP_A = arrayOf(
            "#########################",
            "#o.......#.....#.......o#",
            "#.##.###.#.###.#.###.##.#",
            "#.......................#",
            "#.##.#.#####.#####.#.##.#",
            "#....#.....#.#.....#....#",
            "#.##.#.#..##-##..#.#.##.#",
            "#....#....#HHH#....#....#",
            "          #####          ",
            "#....#.....#.#.....#....#",
            "#.##.#.##.#.#.#.##.#.##.#",
            "#....#....#.#.#....#....#",
            "#.##.###.#.###.#.###.##.#",
            "#o.#.....#.....#.....#.o#",
            "##...###...###...###...##",
            "#...........P...........#",
            "#########################",
        )
        val MAP_B = arrayOf(
            "#########################",
            "#o.......#.....#.......o#",
            "#.......#..###..#.......#",
            "#.......................#",
            "#.##.#.#####.#####.#.##.#",
            "#....#.....#.#.....#....#",
            "#.##.#.#..##-##..#.#.##.#",
            "#....#....#HHH#....#....#",
            "          #####          ",
            "#....#.....#.#.....#....#",
            "#.##...#.#.....#.#...##.#",
            "#....#....#.#.#....#....#",
            "#.......#..###..#.......#",
            "#o.#.....#.....#.....#.o#",
            "#...#...............#...#",
            "#...........P...........#",
            "#########################",
        )
    }

    var state = GameState.TITLE; private set
    var time = 0f; private set

    // --- maze (healed) ---
    val grid = Array(H) { CharArray(W) }      // live: pellets get eaten out of it
    private val pristine = Array(H) { CharArray(W) }
    var pelletsLeft = 0; private set
    private var pelletsTotal = 0
    private var pacStartX = 12; private var pacStartY = 15

    // --- actors ---
    var pacX = 12f; private set
    var pacY = 15f; private set
    var pacDir = 3; private set
    var queuedDir = 3; private set
    var mouthT = 0f; private set
    val ghosts = List(4) { Ghost(it) }

    // --- flow ---
    var level = 1; private set
    var score = 0; private set
    var lives = 3; private set
    var highScore = 0; private set
    var pelletsEaten = 0; private set
    private var wakaAlt = false
    private var extraGiven = false
    var frightT = 0f; private set
    private var chain = 0
    private var waveIdx = 0
    private var waveT = 0f
    private var stateT = 0f
    var fruitT = 0f; private set              // >0 while fruit is out
    private var fruitShown = 0
    var menuIdx = 0; private set
    var pausedIdx = 0; private set
    var flashMsg: String? = null; private set
    private var flashUntil = 0f
    private val rng = Random(System.nanoTime())

    private val scatterX = intArrayOf(23, 1, 23, 1)
    private val scatterY = intArrayOf(1, 1, 15, 15)

    fun boot() {
        highScore = store.highScore
        state = GameState.TITLE
    }

    // ------------------------------------------------------------ maze load

    private fun loadMaze() {
        val src = if (store.mazeIdx == 0) MAP_A else MAP_B
        for (y in 0 until H) for (x in 0 until W) {
            val c = if (y < src.size && x < src[y].length) src[y][x] else '#'
            pristine[y][x] = c
            if (c == 'P') { pacStartX = x; pacStartY = y; pristine[y][x] = '.' }
        }
        // SELF-HEAL phase 1: flood fill from spawn and seal unreachable
        // decorative corridors so they do not look like playable dead ends.
        val reach = Array(H) { BooleanArray(W) }
        val stack = ArrayDeque<IntArray>()
        stack.add(intArrayOf(pacStartX, pacStartY))
        reach[pacStartY][pacStartX] = true
        while (stack.isNotEmpty()) {
            val (cx, cy) = stack.removeLast()
            for (d in 0 until 4) {
                var nx = cx + DX[d]; val ny = cy + DY[d]
                if (ny !in 0 until H) continue
                if (nx < 0) nx = W - 1; if (nx >= W) nx = 0
                if (reach[ny][nx]) continue
                val c = pristine[ny][nx]
                if (c == '#' || c == 'H' || c == '-') continue
                reach[ny][nx] = true
                stack.add(intArrayOf(nx, ny))
            }
        }
        for (y in 0 until H) for (x in 0 until W) {
            if (playerWalkable(pristine[y][x]) && !reach[y][x]) pristine[y][x] = '#'
        }

        // Phase 2: break up every 2x2 walkable block without disconnecting
        // the maze. Pellets remain in crisp, single-tile corridors instead
        // of forming adjacent rows across open rooms.
        removeWideCorridors()

        // Phase 3: recursively eliminate reachable degree-1 branches. Prefer
        // carving through a one-tile wall into another corridor, producing a
        // loop; only seal the tip when there is no safe corridor to join.
        healDeadEnds()

        pelletsTotal = 0
        for (y in 0 until H) for (x in 0 until W) {
            val c = pristine[y][x]
            if (c == '.' || c == 'o') pelletsTotal++
        }
    }

    private fun playerWalkable(c: Char) = c != '#' && c != 'H' && c != '-'

    private fun playerDegree(x: Int, y: Int): Int {
        var degree = 0
        for (d in 0 until 4) {
            val ny = y + DY[d]
            if (ny !in 0 until H) continue
            var nx = x + DX[d]
            if (nx < 0) nx = W - 1
            if (nx >= W) nx = 0
            if (playerWalkable(pristine[ny][nx])) degree++
        }
        return degree
    }

    private fun walkableCount(): Int {
        var count = 0
        for (row in pristine) for (c in row) if (playerWalkable(c)) count++
        return count
    }

    private fun reachableCount(): Int {
        if (!playerWalkable(pristine[pacStartY][pacStartX])) return 0
        val seen = Array(H) { BooleanArray(W) }
        val stack = ArrayDeque<IntArray>()
        stack.add(intArrayOf(pacStartX, pacStartY))
        seen[pacStartY][pacStartX] = true
        var count = 0
        while (stack.isNotEmpty()) {
            val (x, y) = stack.removeLast()
            count++
            for (d in 0 until 4) {
                val ny = y + DY[d]
                if (ny !in 0 until H) continue
                var nx = x + DX[d]
                if (nx < 0) nx = W - 1
                if (nx >= W) nx = 0
                if (!seen[ny][nx] && playerWalkable(pristine[ny][nx])) {
                    seen[ny][nx] = true
                    stack.add(intArrayOf(nx, ny))
                }
            }
        }
        return count
    }

    private fun wideCorridorAt(x: Int, y: Int): Boolean {
        if (x !in 0 until W - 1 || y !in 0 until H - 1) return false
        return playerWalkable(pristine[y][x]) &&
            playerWalkable(pristine[y][x + 1]) &&
            playerWalkable(pristine[y + 1][x]) &&
            playerWalkable(pristine[y + 1][x + 1])
    }

    private fun hasWideCorridorNear(x: Int, y: Int): Boolean {
        for (top in y - 1..y) for (left in x - 1..x) {
            if (wideCorridorAt(left, top)) return true
        }
        return false
    }

    private fun removeWideCorridors() {
        repeat(W * H * 4) {
            var blockX = -1
            var blockY = -1
            find@ for (y in 0 until H - 1) for (x in 0 until W - 1) {
                if (wideCorridorAt(x, y)) {
                    blockX = x; blockY = y
                    break@find
                }
            }
            if (blockX < 0) return

            val candidates = listOf(
                blockX to blockY,
                blockX + 1 to blockY + 1,
                blockX + 1 to blockY,
                blockX to blockY + 1,
            ).sortedByDescending { (x, y) -> playerDegree(x, y) }

            var removed = false
            for ((x, y) in candidates) {
                val protected = (x == pacStartX && y == pacStartY) ||
                    (x == FRUIT_X && y == FRUIT_Y) ||
                    (x == DOOR_X && y == DOOR_Y - 1) || pristine[y][x] == 'o'
                    || isTunnelCell(x, y)
                if (protected) continue
                val old = pristine[y][x]
                pristine[y][x] = '#'
                val connected = reachableCount() == walkableCount()
                val spawnHasExit = playerDegree(pacStartX, pacStartY) >= 2
                if (connected && spawnHasExit) {
                    removed = true
                    break
                }
                pristine[y][x] = old
            }
            // Both supplied mazes always have a safe choice. Stop rather than
            // damage connectivity if a future hand-authored maze does not.
            if (!removed) return
        }
    }

    private fun healDeadEnds() {
        repeat(W * H * 4) {
            var deadX = -1
            var deadY = -1
            find@ for (y in 0 until H) for (x in 0 until W) {
                if (playerWalkable(pristine[y][x]) && playerDegree(x, y) <= 1) {
                    deadX = x; deadY = y
                    break@find
                }
            }
            if (deadX < 0) return

            var carved = false
            for (d in 0 until 4) {
                val wallY = deadY + DY[d]
                val beyondY = wallY + DY[d]
                if (wallY !in 0 until H || beyondY !in 0 until H) continue
                var wallX = deadX + DX[d]
                if (wallX < 0) wallX = W - 1
                if (wallX >= W) wallX = 0
                var beyondX = wallX + DX[d]
                if (beyondX < 0) beyondX = W - 1
                if (beyondX >= W) beyondX = 0
                if (pristine[wallY][wallX] == '#' &&
                    playerWalkable(pristine[beyondY][beyondX])
                ) {
                    pristine[wallY][wallX] = '.'
                    if (!hasWideCorridorNear(wallX, wallY)) {
                        carved = true
                        break
                    }
                    pristine[wallY][wallX] = '#'
                }
            }
            if (!carved) {
                // The spawn itself is never deleted. The normalization phase
                // guarantees it has at least two exits in the supplied maps.
                if (deadX == pacStartX && deadY == pacStartY) return
                pristine[deadY][deadX] = '#'
            }
        }
    }

    /** The centre row wraps across the left/right display edges. Keep this
     * deliberate, pellet-free passage intact while normalizing broad rooms. */
    private fun isTunnelCell(x: Int, y: Int): Boolean =
        y == 8 && (x in 0..9 || x in 15 until W)

    private fun resetPellets() {
        for (y in 0 until H) for (x in 0 until W) grid[y][x] = pristine[y][x]
        pelletsLeft = pelletsTotal
        pelletsEaten = 0
        fruitShown = 0
        fruitT = 0f
    }

    // ------------------------------------------------------------- options

    fun titleRows(): List<Pair<String, String>> = listOf(
        "START" to "",
        "DIFFICULTY" to DIFF_NAMES[store.difficulty],
        "MAZE" to MAZE_NAMES[store.mazeIdx],
        "LIVES" to if (store.livesOpt == 0) "3" else "5",
    )

    fun pausedRows() = listOf("RESUME", "RESTART", "QUIT TO TITLE")

    // --------------------------------------------------------------- input

    fun swipe(dir: Int) {
        when (state) {
            GameState.TITLE -> {
                if (dir == 0 || dir == 1) {
                    menuIdx = (menuIdx + (if (dir == 1) 1 else -1) + 4) % 4
                    host.sfx(Sfx.UI)
                } else adjustOption(if (dir == 3) 1 else -1)
            }
            GameState.PLAY, GameState.READY -> queuedDir = dir
            GameState.PAUSED -> if (dir == 0 || dir == 1) {
                pausedIdx = (pausedIdx + (if (dir == 1) 1 else -1) + 3) % 3
                host.sfx(Sfx.UI)
            }
            else -> {}
        }
    }

    private fun adjustOption(d: Int) {
        when (menuIdx) {
            1 -> store.difficulty = (store.difficulty + d + 3) % 3
            2 -> store.mazeIdx = (store.mazeIdx + d + 2) % 2
            3 -> store.livesOpt = (store.livesOpt + d + 2) % 2
            else -> return
        }
        host.sfx(Sfx.UI, 1.25f)
    }

    fun tap() {
        when (state) {
            GameState.TITLE -> if (menuIdx == 0) startGame() else adjustOption(1)
            GameState.PAUSED -> when (pausedIdx) {
                0 -> { state = GameState.PLAY; host.sfx(Sfx.SELECT); resumeChaseAudio() }
                1 -> { host.sfx(Sfx.SELECT); startGame() }
                else -> { toTitle(); }
            }
            GameState.GAME_OVER -> toTitle()
            else -> {}
        }
    }

    /** Double-tap: pause during play; back/resume elsewhere. */
    fun back() {
        when (state) {
            GameState.PLAY -> {
                state = GameState.PAUSED; pausedIdx = 0
                host.sirenStop(); host.frightStop()
                host.sfx(Sfx.BACK)
            }
            GameState.PAUSED -> { state = GameState.PLAY; resumeChaseAudio(); host.sfx(Sfx.SELECT) }
            GameState.GAME_OVER -> toTitle()
            else -> {}
        }
    }

    private fun toTitle() {
        state = GameState.TITLE
        menuIdx = 0
        host.sirenStop(); host.frightStop()
        host.sfx(Sfx.BACK)
    }

    // ----------------------------------------------------------------- flow

    private fun startGame() {
        loadMaze()
        resetPellets()
        level = 1
        score = 0
        lives = if (store.livesOpt == 0) 3 else 5
        extraGiven = false
        store.games++
        highScore = store.highScore
        host.sfx(Sfx.READY)
        resetActors()
        state = GameState.READY
        stateT = LEVEL_READY_SECS
    }

    private fun resetActors() {
        pacX = pacStartX.toFloat(); pacY = pacStartY.toFloat()
        pacDir = 3; queuedDir = 3
        frightT = 0f; chain = 0
        waveIdx = 0; waveT = 0f
        for ((i, g) in ghosts.withIndex()) {
            g.eyes = false; g.exiting = false
            when (i) {
                0 -> { g.inHouse = false; g.x = DOOR_X.toFloat(); g.y = (DOOR_Y - 1).toFloat(); g.dir = 2 }
                else -> {
                    g.inHouse = true
                    g.x = (10 + i).toFloat(); g.y = 7f; g.dir = 0
                    g.exitAt = time + 1.5f + (i - 1) * 3.5f
                }
            }
        }
    }

    fun update(dt: Float) {
        time += dt
        if (flashMsg != null && time > flashUntil) flashMsg = null
        when (state) {
            GameState.TITLE, GameState.PAUSED, GameState.GAME_OVER -> {}
            GameState.READY -> {
                stateT -= dt
                if (stateT <= 0f) { state = GameState.PLAY; host.sirenStart(); sirenUpdate() }
            }
            GameState.PLAY -> stepPlay(dt)
            GameState.DYING -> {
                mouthT += dt
                stateT -= dt
                if (stateT <= 0f) {
                    if (lives <= 0) {
                        state = GameState.GAME_OVER
                        store.highScore = score
                        highScore = store.highScore
                        host.sfx(Sfx.GAMEOVER)
                    } else {
                        resetActors()
                        state = GameState.READY
                        stateT = LEVEL_READY_SECS
                        host.sfx(Sfx.READY, 1f, 0.6f)
                    }
                }
            }
            GameState.CLEAR -> {
                stateT -= dt
                if (stateT <= 0f) {
                    level++
                    resetPellets()
                    resetActors()
                    state = GameState.READY
                    stateT = LEVEL_READY_SECS
                    host.sfx(Sfx.READY)
                }
            }
        }
    }

    // ----------------------------------------------------------- play step

    private fun stepPlay(dt: Float) {
        mouthT += dt

        // mode waves
        if (frightT > 0f) {
            frightT -= dt
            if (frightT <= 0f) { chain = 0; host.frightStop(); host.sirenStart() }
        } else {
            waveT += dt
            if (waveT >= WAVES[waveIdx]) { waveT = 0f; waveIdx = (waveIdx + 1).coerceAtMost(WAVES.size - 1); reverseAll() }
        }

        // fruit
        if (fruitT > 0f) fruitT -= dt
        if (fruitShown == 0 && pelletsEaten >= 70) { fruitShown = 1; fruitT = 9f; host.sfx(Sfx.FRUIT, 0.8f, 0.7f) }
        if (fruitShown == 1 && pelletsEaten >= 170) { fruitShown = 2; fruitT = 9f; host.sfx(Sfx.FRUIT, 0.8f, 0.7f) }

        val oldPacX = pacX; val oldPacY = pacY
        val oldGhostX = FloatArray(ghosts.size) { ghosts[it].x }
        val oldGhostY = FloatArray(ghosts.size) { ghosts[it].y }
        movePac(dt)
        // Eating the final pellet changes state immediately. Nothing gets a
        // post-clear collision frame that can steal the earned level clear.
        if (state != GameState.PLAY) return
        for (g in ghosts) moveGhost(g, dt)
        collide(oldPacX, oldPacY, oldGhostX, oldGhostY)
    }

    private fun diffGhostMul() = when (store.difficulty) { 0 -> 0.85f; 2 -> 1.08f; else -> 1f }

    private fun pacSpeed() = (5.2f + (level - 1) * 0.15f).coerceAtMost(7f) * 0.75f
    private fun ghostSpeed(g: Ghost): Float {
        var s = (4.9f + (level - 1) * 0.15f).coerceAtMost(6.8f) * diffGhostMul()
        if (g.eyes) return 9f
        if (frightT > 0f) s *= 0.62f
        if (g.y.toInt() == 8 && (g.x < 3f || g.x > W - 4f)) s *= 0.55f   // tunnel crawl
        return s * 0.75f
    }

    /** Can an actor step from tile (x,y) toward dir? */
    private fun open(x: Int, y: Int, dir: Int, ghost: Ghost?): Boolean {
        var nx = x + DX[dir]; val ny = y + DY[dir]
        if (ny !in 0 until H) return false
        if (nx < 0) nx = W - 1; if (nx >= W) nx = 0
        val c = pristine[ny][nx]
        return when (c) {
            '#' -> false
            '-' -> ghost != null && (ghost.exiting || ghost.eyes)
            'H' -> ghost != null && (ghost.inHouse || ghost.eyes)
            else -> true
        }
    }

    /**
     * True only while arriving at a tile centre (or when exactly on it).
     *
     * A plain "near centre" check also fires just after an actor leaves a
     * centre. Snapping in that case cancels a small frame/substep and leaves
     * every actor parked forever. The direction test distinguishes the
     * arrival side from the departure side while retaining a forgiving turn
     * window on uneven X3 frame times.
     */
    private fun approachingCenter(fx: Float, fy: Float, dir: Int): Boolean {
        val cx = fx - Math.round(fx)
        val cy = fy - Math.round(fy)
        if (abs(cx) >= 0.08f || abs(cy) >= 0.08f) return false
        return when (dir) {
            0 -> cy >= 0f // up: approaching from below
            1 -> cy <= 0f // down: approaching from above
            2 -> cx >= 0f // left: approaching from the right
            else -> cx <= 0f // right: approaching from the left
        }
    }

    private fun movePac(dt: Float) {
        val speed = pacSpeed()
        val reverse = opposite(pacDir)
        // Reversals feel immediate in classic maze games and are especially
        // important on a tiny temple pad where another swipe costs time.
        if (queuedDir == reverse && open(Math.round(pacX), Math.round(pacY), queuedDir, null)) {
            pacDir = queuedDir
        }

        // Substep movement so a dropped X3 frame cannot leap completely over
        // a tile center (losing a queued turn or skipping a pellet).
        val distance = speed * dt
        val steps = ceil(distance / MOVE_SUBSTEP).toInt().coerceAtLeast(1)
        val stepDistance = distance / steps
        for (step in 0 until steps) {
            if (state != GameState.PLAY) break
            val tx = Math.round(pacX); val ty = Math.round(pacY)
            if (approachingCenter(pacX, pacY, pacDir)) {
                pacX = tx.toFloat(); pacY = ty.toFloat()
                if (queuedDir != pacDir && open(tx, ty, queuedDir, null)) {
                    pacDir = queuedDir
                }
                if (!open(tx, ty, pacDir, null)) {
                    // Movement is automatic, but a wall stops the player
                    // until a valid turn is swiped (classic maze behavior).
                    break
                }
            }
            pacX += DX[pacDir] * stepDistance
            pacY += DY[pacDir] * stepDistance
            if (pacX < -0.5f) pacX += W
            if (pacX >= W - 0.5f) pacX -= W
            eatAtCurrentTile()
        }
    }

    private fun eatAtCurrentTile() {
        val ex = Math.round(pacX); val ey = Math.round(pacY)
        if (ex in 0 until W && ey in 0 until H) {
            when (grid[ey][ex]) {
                '.' -> {
                    grid[ey][ex] = ' '
                    pelletsLeft--; pelletsEaten++
                    addScore(10)
                    wakaAlt = !wakaAlt
                    host.sfx(if (wakaAlt) Sfx.WAKA1 else Sfx.WAKA2, 1f, 0.55f)
                    sirenUpdate()
                    if (pelletsLeft <= 0) levelClear()
                }
                'o' -> {
                    grid[ey][ex] = ' '
                    pelletsLeft--; pelletsEaten++
                    addScore(50)
                    frightT = FRIGHT_SECS[store.difficulty] * (if (level > 4) 0.7f else 1f)
                    chain = 0
                    reverseAll()
                    host.sfx(Sfx.POWER)
                    host.sirenStop(); host.frightStart()
                    if (pelletsLeft <= 0) levelClear()
                }
            }
            // fruit
            if (fruitT > 0f && ex == FRUIT_X && ey == FRUIT_Y) {
                fruitT = 0f
                val v = 100 * level.coerceAtMost(5)
                addScore(v)
                flash("+$v")
                host.sfx(Sfx.FRUIT, 1.2f)
            }
        }
    }

    private fun moveGhost(g: Ghost, dt: Float) {
        // house: bob until exit time, then climb out through the door
        if (g.inHouse && !g.eyes) {
            if (!g.exiting) {
                g.y = 7f + 0.18f * kotlin.math.sin(time * 3f + g.type)
                if (time >= g.exitAt) { g.exiting = true; g.x = Math.round(g.x).toFloat(); g.y = 7f }
                return
            }
            // move to door column, then up and out
            if (abs(g.x - DOOR_X) > 0.05f) {
                g.x += (if (g.x < DOOR_X) 1 else -1) * 2.5f * dt
            } else {
                g.x = DOOR_X.toFloat()
                g.y -= 2.5f * dt
                if (g.y <= DOOR_Y - 1) { g.y = (DOOR_Y - 1).toFloat(); g.inHouse = false; g.exiting = false; g.dir = if (rng.nextBoolean()) 2 else 3 }
            }
            return
        }
        // eyes reaching the door sink back in, then revive
        if (g.eyes && abs(g.x - DOOR_X) < 0.15f && abs(g.y - (DOOR_Y - 1)) < 0.15f) {
            g.eyes = false
            g.inHouse = true
            g.exiting = true      // climb right back out
            g.x = DOOR_X.toFloat(); g.y = 7f
            host.sfx(Sfx.REVIVE, 1f, 0.6f)
            return
        }

        val speed = ghostSpeed(g)
        val distance = speed * dt
        val steps = ceil(distance / MOVE_SUBSTEP).toInt().coerceAtLeast(1)
        val stepDistance = distance / steps
        for (step in 0 until steps) {
            val tx = Math.round(g.x); val ty = Math.round(g.y)
            if (approachingCenter(g.x, g.y, g.dir)) {
                g.x = tx.toFloat(); g.y = ty.toFloat()
                val rev = opposite(g.dir)
                var best = -1
                if (frightT > 0f && !g.eyes) {
                    // frightened: stumble randomly (never reverse unless dead end)
                    val opts = ArrayList<Int>(3)
                    for (d in 0 until 4) if (d != rev && open(tx, ty, d, g)) opts.add(d)
                    best = if (opts.isEmpty()) rev else opts[rng.nextInt(opts.size)]
                } else {
                    val (gtX, gtY) = targetOf(g)
                    var bestD = Float.MAX_VALUE
                    for (d in 0 until 4) {
                        if (d != rev && open(tx, ty, d, g)) {
                            val nx = tx + DX[d]; val ny = ty + DY[d]
                            val dd = (nx - gtX) * (nx - gtX) + (ny - gtY) * (ny - gtY)
                            if (dd < bestD) { bestD = dd; best = d }
                        }
                    }
                    if (best < 0) best = if (open(tx, ty, rev, g)) rev else g.dir
                }
                g.dir = best
            }
            g.x += DX[g.dir] * stepDistance
            g.y += DY[g.dir] * stepDistance
            if (g.x < -0.5f) g.x += W
            if (g.x >= W - 0.5f) g.x -= W
        }
    }

    /** The four brains, faithful in spirit. Returns a target tile. */
    private fun targetOf(g: Ghost): Pair<Float, Float> {
        if (g.eyes) return DOOR_X.toFloat() to (DOOR_Y - 1).toFloat()
        val scatter = waveIdx % 2 == 0
        if (scatter && g.type != 0) return scatterX[g.type].toFloat() to scatterY[g.type].toFloat()
        return when (g.type) {
            0 -> pacX to pacY                                     // shadow: right behind you
            1 -> pacX + DX[pacDir] * 4 to pacY + DY[pacDir] * 4   // speedy: ambush ahead
            2 -> {                                                // bashful: mirrored lunge
                val ax = pacX + DX[pacDir] * 2; val ay = pacY + DY[pacDir] * 2
                (2 * ax - ghosts[0].x) to (2 * ay - ghosts[0].y)
            }
            else -> {                                             // pokey: shy when close
                val d2 = (g.x - pacX) * (g.x - pacX) + (g.y - pacY) * (g.y - pacY)
                if (d2 > 64f) pacX to pacY else scatterX[3].toFloat() to scatterY[3].toFloat()
            }
        }
    }

    private fun reverseAll() {
        for (g in ghosts) if (!g.inHouse && !g.eyes) {
            g.dir = opposite(g.dir)
        }
    }

    private fun opposite(dir: Int) = when (dir) { 0 -> 1; 1 -> 0; 2 -> 3; else -> 2 }

    private fun collide(oldPacX: Float, oldPacY: Float, oldGhostX: FloatArray, oldGhostY: FloatArray) {
        for ((i, g) in ghosts.withIndex()) {
            if (g.inHouse) continue
            var startX = tunnelDelta(oldGhostX[i] - oldPacX)
            var endX = tunnelDelta(g.x - pacX)
            // Keep the end displacement on the same wrapped branch as start.
            if (endX - startX > W / 2f) endX -= W
            if (endX - startX < -W / 2f) endX += W
            val startY = oldGhostY[i] - oldPacY
            val endY = g.y - pacY
            val vx = endX - startX
            val vy = endY - startY
            val vv = vx * vx + vy * vy
            val t = if (vv > 0.0001f)
                (-(startX * vx + startY * vy) / vv).coerceIn(0f, 1f) else 0f
            val dx = startX + vx * t
            val dy = startY + vy * t
            if (dx * dx + dy * dy > 0.42f) continue
            if (g.eyes) continue
            if (frightT > 0f) {
                val v = 200 shl chain
                chain = (chain + 1).coerceAtMost(3)
                addScore(v)
                flash("+$v")
                g.eyes = true
                host.sfx(Sfx.GHOST)
            } else {
                // caught
                lives--
                state = GameState.DYING
                stateT = 1.6f
                mouthT = 0f
                host.sirenStop(); host.frightStop()
                host.sfx(Sfx.DEATH)
                return
            }
        }
    }

    private fun tunnelDelta(raw: Float): Float {
        var d = raw
        while (d > W / 2f) d -= W
        while (d < -W / 2f) d += W
        return d
    }

    private fun levelClear() {
        state = GameState.CLEAR
        stateT = 2.2f
        host.sirenStop(); host.frightStop()
        host.sfx(Sfx.CLEARJ)
    }

    private fun sirenUpdate() {
        val frac = 1f - pelletsLeft.toFloat() / pelletsTotal.coerceAtLeast(1)
        host.sirenRate(0.8f + frac * 0.7f)
    }

    private fun resumeChaseAudio() {
        if (frightT > 0f) {
            host.sirenStop()
            host.frightStart()
        } else {
            host.frightStop()
            host.sirenStart()
            sirenUpdate()
        }
    }

    private fun addScore(n: Int) {
        score += n
        if (!extraGiven && score >= 10000) {
            extraGiven = true
            lives++
            flash("EXTRA LIFE!")
            host.sfx(Sfx.EXTRA)
        }
        if (score > highScore) { highScore = score; store.highScore = score }
    }

    private fun flash(s: String) { flashMsg = s; flashUntil = time + 1.4f }

    fun onAppPause() {
        if (state == GameState.PLAY) {
            state = GameState.PAUSED
            host.sirenStop(); host.frightStop()
        }
    }

}
