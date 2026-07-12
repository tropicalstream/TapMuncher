package com.tapmuncher.gl

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.tapmuncher.engine.GHOST_HUES
import com.tapmuncher.engine.Game
import com.tapmuncher.engine.GameState
import com.tapmuncher.engine.Ghost
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * OpenGL ES 3.0 renderer for TapMuncher: the maze as a glowing neon table —
 * wall edges traced as vector lines that slowly cycle hue, pellets as soft
 * points, the muncher as a chomping arc, pursuers as dome-and-skirt outlines.
 * Straight-on tilted camera (58°); black = transparent on the waveguide;
 * one draw per eye into side-by-side viewports.
 */
class GLRenderer(private val game: Game) : GLSurfaceView.Renderer {

    var sbs = false

    private var program = 0
    private var aPos = 0; private var aColor = 0
    private var uMVP = 0; private var uPointSize = 0; private var uPoint = 0
    private var width = 1; private var height = 1
    private var lastNanos = 0L

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val mvp = FloatArray(16)
    private val ortho = FloatArray(16)
    private val rgb = FloatArray(3)

    private val lines = Batch(22000)
    private val fx = Batch(5000)
    private val hud = Batch(5000)

    private val stars: FloatArray = Random(9).let { r ->
        FloatArray(70 * 3) { i ->
            when (i % 3) {
                0 -> r.nextFloat() * 70f - 35f
                1 -> -4f - r.nextFloat() * 9f
                else -> r.nextFloat() * 55f - 27f
            }
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        program = buildProgram(VERT, FRAG)
        aPos = GLES30.glGetAttribLocation(program, "aPos")
        aColor = GLES30.glGetAttribLocation(program, "aColor")
        uMVP = GLES30.glGetUniformLocation(program, "uMVP")
        uPointSize = GLES30.glGetUniformLocation(program, "uPointSize")
        uPoint = GLES30.glGetUniformLocation(program, "uPoint")
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
        lastNanos = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w; height = h
        Matrix.orthoM(ortho, 0, 0f, 640f, 480f, 0f, -1f, 1f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.016f else ((now - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = now
        game.update(dt)

        buildScene(); buildHud()

        GLES30.glViewport(0, 0, width, height)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glUseProgram(program)

        val eyes = if (sbs) 2 else 1
        val vw = if (sbs) width / 2 else width
        val aspect = vw.toFloat() / height.toFloat()
        Matrix.orthoM(proj, 0, -V * aspect, V * aspect, -V, V, 1f, 300f)
        Matrix.setLookAtM(view, 0, 0f, CAM_D * TILT_SIN, CAM_D * TILT_COS, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(mvp, 0, proj, 0, view, 0)

        for (e in 0 until eyes) {
            GLES30.glViewport(e * vw, 0, vw, height)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, mvp, 0)
            GLES30.glUniform1f(uPoint, 0f)
            lines.draw(GLES30.GL_LINES)
            GLES30.glUniform1f(uPoint, 1f)
            GLES30.glUniform1f(uPointSize, 10f); fx.draw(GLES30.GL_POINTS)
            GLES30.glUniformMatrix4fv(uMVP, 1, false, ortho, 0)
            GLES30.glUniform1f(uPoint, 0f)
            hud.draw(GLES30.GL_LINES)
        }
    }

    private fun wx(gx: Float) = (gx - (Game.W - 1) / 2f) * TILE
    private fun wz(gy: Float) = (gy - (Game.H - 1) / 2f) * TILE

    // ------------------------------------------------------- scene build

    private fun buildScene() {
        lines.reset(); fx.reset()
        buildStars()
        when (game.state) {
            GameState.TITLE -> buildTitleParade()
            else -> {
                buildMaze()
                buildPellets()
                if (game.fruitT > 0f) buildFruit()
                for (g in game.ghosts) buildGhost(g)
                buildPac()
            }
        }
    }

    private fun buildStars() {
        val h = game.time * 0.02f
        for (i in 0 until stars.size / 3) {
            hsv((h + i * 0.011f) % 1f, 0.35f, 0.8f)
            val tw = 0.22f + 0.22f * sin(game.time * 1.4f + i)
            fx.v(stars[i * 3], stars[i * 3 + 1], stars[i * 3 + 2], rgb[0], rgb[1], rgb[2], tw)
        }
    }

    /** Maze walls: trace only edges that face a walkable cell — clean neon outlines. */
    private fun buildMaze() {
        val flash = game.state == GameState.CLEAR
        val bright = if (flash) 0.7f + 0.3f * sin(game.time * 14f) else 1f
        hsv((game.time * 0.03f + 0.6f) % 1f, if (flash) 0.25f else 0.8f, bright)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val a = 0.85f
        val hh = TILE / 2f
        for (y in 0 until Game.H) for (x in 0 until Game.W) {
            if (!isWall(x, y)) continue
            val cx = wx(x.toFloat()); val cz = wz(y.toFloat())
            // an edge is drawn where the neighbour is NOT a wall
            if (!isWall(x, y - 1)) lines.line(cx - hh, 0f, cz - hh, cx + hh, 0f, cz - hh, r, g, b, a)
            if (!isWall(x, y + 1)) lines.line(cx - hh, 0f, cz + hh, cx + hh, 0f, cz + hh, r, g, b, a)
            if (!isWall(x - 1, y)) lines.line(cx - hh, 0f, cz - hh, cx - hh, 0f, cz + hh, r, g, b, a)
            if (!isWall(x + 1, y)) lines.line(cx + hh, 0f, cz - hh, cx + hh, 0f, cz + hh, r, g, b, a)
        }
        // ghost-house door glows softly
        hsv(0.6f, 0.3f, 1f)
        val dx = wx(Game.DOOR_X.toFloat()); val dz = wz(Game.DOOR_Y.toFloat())
        lines.line(dx - hh, 0f, dz, dx + hh, 0f, dz, rgb[0], rgb[1], rgb[2], 0.35f + 0.2f * sin(game.time * 3f))
    }

    private fun isWall(x: Int, y: Int): Boolean {
        if (y < 0 || y >= Game.H) return false
        if (x < 0 || x >= Game.W) return false
        return game.grid[y][x] == '#'
    }

    private fun buildPellets() {
        for (y in 0 until Game.H) for (x in 0 until Game.W) {
            when (game.grid[y][x]) {
                '.' -> {
                    hsv(0.12f, 0.25f, 1f)
                    fx.v(wx(x.toFloat()), 0.1f, wz(y.toFloat()), rgb[0], rgb[1], rgb[2], 0.6f)
                }
                'o' -> {
                    val pulse = 0.5f + 0.5f * sin(game.time * 6f + x)
                    hsv((game.time * 0.3f) % 1f, 0.6f, 1f)
                    fx.v(wx(x.toFloat()), 0.15f, wz(y.toFloat()), rgb[0], rgb[1], rgb[2], 0.5f + 0.5f * pulse)
                    ring(wx(x.toFloat()), 0.05f, wz(y.toFloat()), 0.42f * TILE * (0.8f + 0.2f * pulse), 8, rgb[0], rgb[1], rgb[2], 0.5f * pulse + 0.2f)
                }
            }
        }
    }

    private fun buildFruit() {
        val x = wx(Game.FRUIT_X.toFloat()); val z = wz(Game.FRUIT_Y.toFloat())
        val bob = 0.1f * sin(game.time * 4f)
        hsv(0.98f, 0.85f, 1f)
        ring(x - 0.22f, 0.25f + bob, z + 0.1f, 0.26f, 8, rgb[0], rgb[1], rgb[2], 0.95f)
        ring(x + 0.22f, 0.25f + bob, z + 0.18f, 0.26f, 8, rgb[0], rgb[1], rgb[2], 0.95f)
        hsv(0.3f, 0.7f, 0.9f)
        lines.line(x - 0.15f, 0.5f + bob, z + 0.05f, x + 0.3f, 0.95f + bob, z - 0.1f, rgb[0], rgb[1], rgb[2], 0.9f)
        fx.v(x, 0.4f + bob, z, 1f, 1f, 1f, 0.7f)
    }

    private fun buildPac() {
        if (game.state == GameState.GAME_OVER) return
        val x = wx(game.pacX); val z = wz(game.pacY)
        val rr = 0.58f * TILE
        // mouth angle: chomp in play, unhinge during the death spin
        val mouth = if (game.state == GameState.DYING)
            (0.2f + 2.6f * (game.mouthT / 1.6f)).coerceAtMost(3.0f)
        else 0.25f + 0.5f * abs(sin(game.mouthT * 9f))
        val face = when (game.pacDir) { 0 -> -PI.toFloat() / 2f; 1 -> PI.toFloat() / 2f; 2 -> PI.toFloat(); else -> 0f }
        hsv(0.14f, 0.9f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val segs = 16
        val start = face + mouth
        val end = face + 2f * PI.toFloat() - mouth
        var pa = start
        for (i in 1..segs) {
            val na = start + (end - start) * i / segs
            lines.line(
                x + cos(pa) * rr, 0.3f, z + sin(pa) * rr,
                x + cos(na) * rr, 0.3f, z + sin(na) * rr, r, g, b, 1f
            )
            pa = na
        }
        // lips to center
        lines.line(x + cos(start) * rr, 0.3f, z + sin(start) * rr, x, 0.3f, z, r, g, b, 1f)
        lines.line(x + cos(end) * rr, 0.3f, z + sin(end) * rr, x, 0.3f, z, r, g, b, 1f)
    }

    private fun buildGhost(g: Ghost) {
        val x = wx(g.x); val z = wz(g.y)
        val rr = 0.55f * TILE
        val frightened = game.frightT > 0f && !g.eyes
        val flashWhite = frightened && game.frightT < 2f && ((game.time * 6f).toInt() and 1) == 0
        if (!g.eyes) {
            when {
                flashWhite -> { rgb[0] = 1f; rgb[1] = 1f; rgb[2] = 1f }
                frightened -> hsv(0.62f, 0.8f, 1f)
                else -> hsv(GHOST_HUES[g.type], 0.85f, 1f)
            }
            val r = rgb[0]; val gg = rgb[1]; val b = rgb[2]
            // dome (upper half toward -z)
            var px = x + rr; var pz = z
            val segs = 8
            for (i in 1..segs) {
                val a = PI.toFloat() * i / segs
                val vx = x + cos(a) * rr; val vz = z - sin(a) * rr
                lines.line(px, 0.3f, pz, vx, 0.3f, vz, r, gg, b, 0.95f)
                px = vx; pz = vz
            }
            // sides + zigzag skirt (bottom toward +z), wiggling as it moves
            val skZ = z + rr * 0.85f
            val wig = sin(game.time * 10f + g.type) * 0.06f
            lines.line(x - rr, 0.3f, z, x - rr, 0.3f, skZ, r, gg, b, 0.95f)
            lines.line(x + rr, 0.3f, z, x + rr, 0.3f, skZ, r, gg, b, 0.95f)
            var sx = x - rr
            val teeth = 4
            for (i in 0 until teeth) {
                val nx = x - rr + (2f * rr) * (i + 0.5f) / teeth
                val nx2 = x - rr + (2f * rr) * (i + 1f) / teeth
                lines.line(sx, 0.3f, skZ, nx, 0.3f, skZ + 0.18f + wig, r, gg, b, 0.95f)
                lines.line(nx, 0.3f, skZ + 0.18f + wig, nx2, 0.3f, skZ, r, gg, b, 0.95f)
                sx = nx2
            }
        }
        // eyes always (they're what's left when eaten)
        val look = 0.12f
        val ex = DXF[g.dir] * look; val ez = DZF[g.dir] * look
        fx.v(x - 0.2f + ex, 0.35f, z - 0.1f + ez, 1f, 1f, 1f, 1f)
        fx.v(x + 0.2f + ex, 0.35f, z - 0.1f + ez, 1f, 1f, 1f, 1f)
    }

    /** The intro parade: pursuers chase the muncher across the title, forever. */
    private fun buildTitleParade() {
        val span = 34f
        val px = ((game.time * 5f) % span) - span / 2f
        for (k in 0 until 4) buildGhostAt(px - 2.2f - k * 1.9f, 4.5f, k)
        buildPacAt(px, 4.5f)
    }

    private fun buildPacAt(worldX: Float, worldZ: Float) {
        val rr = 0.75f
        val mouth = 0.25f + 0.5f * abs(sin(game.time * 9f))
        hsv(0.14f, 0.9f, 1f)
        val r = rgb[0]; val g = rgb[1]; val b = rgb[2]
        val segs = 14
        val start = mouth
        val end = 2f * PI.toFloat() - mouth
        var pa = start
        for (i in 1..segs) {
            val na = start + (end - start) * i / segs
            lines.line(worldX + cos(pa) * rr, 0.3f, worldZ + sin(pa) * rr, worldX + cos(na) * rr, 0.3f, worldZ + sin(na) * rr, r, g, b, 1f)
            pa = na
        }
        lines.line(worldX + cos(start) * rr, 0.3f, worldZ + sin(start) * rr, worldX, 0.3f, worldZ, r, g, b, 1f)
        lines.line(worldX + cos(end) * rr, 0.3f, worldZ + sin(end) * rr, worldX, 0.3f, worldZ, r, g, b, 1f)
    }

    private fun buildGhostAt(worldX: Float, worldZ: Float, type: Int) {
        val rr = 0.7f
        hsv(GHOST_HUES[type], 0.85f, 1f)
        val r = rgb[0]; val gg = rgb[1]; val b = rgb[2]
        var px = worldX + rr; var pz = worldZ
        for (i in 1..8) {
            val a = PI.toFloat() * i / 8
            val vx = worldX + cos(a) * rr; val vz = worldZ - sin(a) * rr
            lines.line(px, 0.3f, pz, vx, 0.3f, vz, r, gg, b, 0.95f)
            px = vx; pz = vz
        }
        val skZ = worldZ + rr * 0.85f
        lines.line(worldX - rr, 0.3f, worldZ, worldX - rr, 0.3f, skZ, r, gg, b, 0.95f)
        lines.line(worldX + rr, 0.3f, worldZ, worldX + rr, 0.3f, skZ, r, gg, b, 0.95f)
        var sx = worldX - rr
        for (i in 0 until 4) {
            val nx = worldX - rr + (2f * rr) * (i + 0.5f) / 4
            val nx2 = worldX - rr + (2f * rr) * (i + 1f) / 4
            lines.line(sx, 0.3f, skZ, nx, 0.3f, skZ + 0.2f, r, gg, b, 0.95f)
            lines.line(nx, 0.3f, skZ + 0.2f, nx2, 0.3f, skZ, r, gg, b, 0.95f)
            sx = nx2
        }
        fx.v(worldX - 0.22f, 0.35f, worldZ - 0.1f, 1f, 1f, 1f, 1f)
        fx.v(worldX + 0.22f, 0.35f, worldZ - 0.1f, 1f, 1f, 1f, 1f)
    }

    private fun ring(x: Float, y: Float, z: Float, rad: Float, seg: Int, r: Float, g: Float, b: Float, a: Float) {
        var px = x + rad; var pz = z
        for (i in 1..seg) {
            val ang = i * (6.2832f / seg)
            val vx = x + cos(ang) * rad; val vz = z + sin(ang) * rad
            lines.line(px, y, pz, vx, y, vz, r, g, b, a)
            px = vx; pz = vz
        }
    }

    // -------------------------------------------------------------- hud

    private val sink = object : StrokeFont.LineSink {
        var cr = 1f; var cg = 1f; var cb = 1f; var ca = 1f
        override fun line(x0: Float, y0: Float, x1: Float, y1: Float) { hud.line(x0, y0, 0f, x1, y1, 0f, cr, cg, cb, ca) }
    }

    private fun text(s: String, cx: Float, y: Float, scale: Float, r: Float, g: Float, b: Float, a: Float = 1f, center: Boolean = true) {
        val x = if (center) cx - StrokeFont.width(s, scale) / 2f else cx
        sink.cr = r; sink.cg = g; sink.cb = b; sink.ca = a
        StrokeFont.draw(s, x, y, scale, sink)
    }

    private fun buildHud() {
        hud.reset()
        val pulse = 0.55f + 0.45f * sin(game.time * 4f)
        hsv(game.time * 0.05f, 0.8f, 1f)
        val hr = rgb[0]; val hg = rgb[1]; val hb = rgb[2]

        when (game.state) {
            GameState.TITLE -> {
                text("TAPMUNCHER", 320f, 100f, 4.2f, hr, hg, hb)
                text("A NEON MAZE-MUNCHER REMIX", 320f, 142f, 1.4f, 0.7f, 0.9f, 1f)
                val rows = game.titleRows()
                for ((i, row) in rows.withIndex()) {
                    val y = 250f + i * 42f
                    val sel = i == game.menuIdx
                    if (sel) {
                        hsv((game.time * 0.3f) % 1f, 0.8f, 1f)
                        text("> ${row.first}${if (row.second.isEmpty()) "" else "  " + row.second} <", 320f, y, 1.8f, rgb[0], rgb[1], rgb[2])
                    } else {
                        text("${row.first}${if (row.second.isEmpty()) "" else "  " + row.second}", 320f, y, 1.5f, 0.6f, 0.65f, 0.75f)
                    }
                }
                if (game.highScore > 0) text("HI ${game.highScore}", 320f, 205f, 1.5f, 0.6f, 1f, 0.7f)
                text("SWIPE - TAP START - DOUBLE-TAP PAUSE", 320f, 442f, 1.25f, 1f, 1f, 1f, pulse)
            }
            GameState.PAUSED -> {
                bar()
                text("PAUSED", 320f, 170f, 2.6f, 1f, 0.85f, 0.4f)
                for ((i, row) in game.pausedRows().withIndex()) {
                    val y = 230f + i * 40f
                    if (i == game.pausedIdx) {
                        hsv((game.time * 0.3f) % 1f, 0.8f, 1f)
                        text("> $row <", 320f, y, 1.7f, rgb[0], rgb[1], rgb[2])
                    } else text(row, 320f, y, 1.45f, 0.6f, 0.65f, 0.75f)
                }
            }
            GameState.READY -> { bar(); text("READY!", 320f, 265f, 2.4f, 1f, 0.9f, 0.3f, pulse) }
            GameState.GAME_OVER -> {
                bar()
                text("GAME OVER", 320f, 240f, 3f, 1f, 0.4f, 0.35f)
                text("SCORE ${game.score} - HI ${game.highScore}", 320f, 292f, 1.6f, 1f, 1f, 1f)
                text("TAP FOR TITLE", 320f, 360f, 1.6f, 0.5f, 1f, 0.6f, pulse)
            }
            GameState.CLEAR -> { bar(); text("MAZE CLEARED!", 320f, 265f, 2.4f, 0.5f, 1f, 0.6f, pulse) }
            else -> bar()
        }

        game.flashMsg?.let {
            text(it, 320f, 210f, 1.8f, 0.5f, 1f, 1f, 0.6f + 0.4f * pulse)
        }
    }

    private fun bar() {
        text("${game.score}", 16f, 38f, 2f, 1f, 1f, 1f, 1f, center = false)
        val hi = "HI ${game.highScore}"
        text(hi, 320f - StrokeFont.width(hi, 1.4f) / 2f, 38f, 1.4f, 0.7f, 0.85f, 1f, 1f, center = false)
        val lv = "L${game.level}"
        text(lv, 626f - StrokeFont.width(lv, 1.6f), 38f, 1.6f, 0.6f, 1f, 0.7f, 1f, center = false)
        // lives as little wedge glyphs, bottom-left
        for (i in 0 until (game.lives - 1).coerceIn(0, 6)) {
            val cx = 26f + i * 30f
            val cy = 456f
            hud.line(cx - 9f, cy - 8f, 0f, cx + 9f, cy, 0f, 1f, 0.85f, 0.25f, 1f)
            hud.line(cx - 9f, cy + 8f, 0f, cx + 9f, cy, 0f, 1f, 0.85f, 0.25f, 1f)
            hud.line(cx - 9f, cy - 8f, 0f, cx - 9f, cy + 8f, 0f, 1f, 0.85f, 0.25f, 0.8f)
        }
    }

    // ------------------------------------------------------- gl helpers

    private fun hsv(hh: Float, s: Float, v: Float) {
        val h6 = ((hh % 1f + 1f) % 1f) * 6f
        val i = h6.toInt(); val f = h6 - i
        val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
        when (i % 6) {
            0 -> { rgb[0] = v; rgb[1] = t; rgb[2] = p }
            1 -> { rgb[0] = q; rgb[1] = v; rgb[2] = p }
            2 -> { rgb[0] = p; rgb[1] = v; rgb[2] = t }
            3 -> { rgb[0] = p; rgb[1] = q; rgb[2] = v }
            4 -> { rgb[0] = t; rgb[1] = p; rgb[2] = v }
            else -> { rgb[0] = v; rgb[1] = p; rgb[2] = q }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, v); GLES30.glAttachShader(p, f); GLES30.glLinkProgram(p)
        val ok = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("TapMuncher", "link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
        val ok = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("TapMuncher", "compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    inner class Batch(maxVerts: Int) {
        private val fb: FloatBuffer = ByteBuffer.allocateDirect(maxVerts * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        private val cap = maxVerts
        var count = 0; private set
        fun reset() { fb.position(0); count = 0 }
        fun v(x: Float, y: Float, z: Float, r: Float, g: Float, b: Float, a: Float) {
            if (count >= cap) return
            fb.put(x); fb.put(y); fb.put(z); fb.put(r); fb.put(g); fb.put(b); fb.put(a); count++
        }
        fun line(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, g: Float, b: Float, a: Float) {
            v(x0, y0, z0, r, g, b, a); v(x1, y1, z1, r, g, b, a)
        }
        fun draw(mode: Int) {
            if (count == 0) return
            fb.position(0); GLES30.glVertexAttribPointer(aPos, 3, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aPos)
            fb.position(3); GLES30.glVertexAttribPointer(aColor, 4, GLES30.GL_FLOAT, false, 28, fb); GLES30.glEnableVertexAttribArray(aColor)
            GLES30.glDrawArrays(mode, 0, count)
        }
    }

    companion object {
        private const val TILE = 1.35f
        private const val V = 13f
        private const val TILT_SIN = 0.8480f   // sin 58°
        private const val TILT_COS = 0.5299f   // cos 58°
        private const val CAM_D = 60f
        private val DXF = floatArrayOf(0f, 0f, -0.22f, 0.22f)
        private val DZF = floatArrayOf(-0.22f, 0.22f, 0f, 0f)

        private const val VERT = """#version 300 es
        in vec3 aPos; in vec4 aColor; uniform mat4 uMVP; uniform float uPointSize; out vec4 vColor;
        void main() { gl_Position = uMVP * vec4(aPos, 1.0); gl_PointSize = uPointSize; vColor = aColor; }"""
        private const val FRAG = """#version 300 es
        precision mediump float; in vec4 vColor; uniform float uPoint; out vec4 fragColor;
        void main() {
            if (uPoint > 0.5) { vec2 d = gl_PointCoord - vec2(0.5); float r2 = dot(d, d); if (r2 > 0.25) discard; fragColor = vec4(vColor.rgb, vColor.a * (1.0 - r2 * 4.0)); }
            else { fragColor = vColor; }
        }"""
    }
}
