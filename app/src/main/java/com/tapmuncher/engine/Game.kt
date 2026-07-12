package com.tapmuncher.engine

import com.tapmuncher.SettingsStore
import com.tapmuncher.audio.Sfx
import kotlin.math.abs
import kotlin.math.floor
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
 * Hand-authored mazes are SELF-HEALED at load: a flood fill from the spawn
 * turns unreachable pellets into empty space, so an authoring slip can never
 * strand a pellet and soft-lock a level.
 */
class Game(private val store: SettingsStore, private val host: GameHost) {

    companion object {
        const val W = 25
        const val H = 17
        val DX = intArrayOf(0, 0, -1, 1)   // 0 up, 1 down, 2 left, 3 right
        val DY = intArrayOf(-1, 1, 0, 0)
        val WAVES = floatArrayOf(7f, 20f, 7f, 20f, 5f, 20f, 5f, 1e9f)
        val FRIGHT_SECS = floatArrayOf(7f, 5f, 3f)
        val DIFF_NAMES = arrayOf("EASY", "NORMAL", "HARD")
        val MAZE_NAMES = arrayOf("NEON", "OPEN")
        const val DOOR_X = 12; const val DOOR_Y = 6
        const val FRUIT_X = 12; const val FRUIT_Y = 9

        val MAP_A = arrayOf(
            "#########################",
            "#o.......#.....#.......o#",
            "#.##.###.#.###.#.###.##.#",
            "#.......................#",
            "#.##.#.#####.#####.#.##.#",
            "#....#.....#.#.....#....#",
            "#.##.#.#..##-##..#.#.##.#",
            "#....#....#HHH#....#....#",
            "      #...#####...#      ",
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
            "      #...#####...#      ",
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
        // SELF-HEAL: flood fill from spawn; strand-proof the pellets.
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
        pelletsTotal = 0
        for (y in 0 until H) for (x in 0 until W) {
            var c = pristine[y][x]
            if ((c == '.' || c == 'o') && !reach[y][x]) c = ' '
            pristine[y][x] = c
            if (c == '.' || c == 'o') pelletsTotal++
        }
    }

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
                0 -> { state = GameState.PLAY; host.sfx(Sfx.SELECT); host.sirenStart() }
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
            GameState.PAUSED -> { state = GameState.PLAY; host.sirenStart(); host.sfx(Sfx.SELECT) }
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
        stateT = 1.8f
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
                        stateT = 1.5f
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
                    stateT = 1.5f
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

        movePac(dt)
        for (g in ghosts) moveGhost(g, dt)
        collide()
    }

    private fun diffGhostMul() = when (store.difficulty) { 0 -> 0.85f; 2 -> 1.08f; else -> 1f }

    private fun pacSpeed() = (5.2f + (level - 1) * 0.15f).coerceAtMost(7f)
    private fun ghostSpeed(g: Ghost): Float {
        var s = (4.9f + (level - 1) * 0.15f).coerceAtMost(6.8f) * diffGhostMul()
        if (g.eyes) return 9f
        if (frightT > 0f) s *= 0.62f
        if (g.y.toInt() == 8 && (g.x < 3f || g.x > W - 4f)) s *= 0.55f   // tunnel crawl
        return s
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

    private fun atCenter(fx: Float, fy: Float): Boolean {
        val cx = fx - floor(fx + 0.5f)
        val cy = fy - floor(fy + 0.5f)
        return abs(cx) < 0.08f && abs(cy) < 0.08f
    }

    private fun movePac(dt: Float) {
        val speed = pacSpeed()
        val tx = Math.round(pacX); val ty = Math.round(pacY)
        if (atCenter(pacX, pacY)) {
            if (queuedDir != pacDir && open(tx, ty, queuedDir, null)) {
                pacDir = queuedDir
                pacX = tx.toFloat(); pacY = ty.toFloat()
            }
            if (!open(tx, ty, pacDir, null)) { pacX = tx.toFloat(); pacY = ty.toFloat(); return }
        }
        pacX += DX[pacDir] * speed * dt
        pacY += DY[pacDir] * speed * dt
        // tunnel wrap
        if (pacX < -0.5f) pacX += W; if (pacX >= W - 0.5f) pacX -= W

        // eat what's underfoot
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
        val tx = Math.round(g.x); val ty = Math.round(g.y)
        if (atCenter(g.x, g.y)) {
            val rev = when (g.dir) { 0 -> 1; 1 -> 0; 2 -> 3; else -> 2 }
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
            if (best != g.dir) { g.dir = best; g.x = tx.toFloat(); g.y = ty.toFloat() }
        }
        g.x += DX[g.dir] * speed * dt
        g.y += DY[g.dir] * speed * dt
        if (g.x < -0.5f) g.x += W; if (g.x >= W - 0.5f) g.x -= W
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
            g.dir = when (g.dir) { 0 -> 1; 1 -> 0; 2 -> 3; else -> 2 }
        }
    }

    private fun collide() {
        for (g in ghosts) {
            if (g.inHouse) continue
            val dx = g.x - pacX; val dy = g.y - pacY
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
