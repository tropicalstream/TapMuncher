package com.tapmuncher

import android.app.Activity
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.tapmuncher.audio.Sfx
import com.tapmuncher.engine.Game
import com.tapmuncher.engine.GameHost
import kotlin.math.abs
import kotlin.math.max

/**
 * TapMuncher. Steering must be INSTANT — swipes queue a turn with no delay.
 * Taps are only for menus, so they can afford the double-tap window:
 *  - SWIPE (4-way) = queue a turn / walk menus.
 *  - TAP = select (deferred ~260 ms so a double can cancel it).
 *  - DOUBLE-TAP = pause / back.
 */
class MainActivity : Activity(), GameHost {

    private lateinit var store: SettingsStore
    private lateinit var sfx: Sfx
    private lateinit var game: Game
    private lateinit var glView: GLSurfaceView
    private lateinit var renderer: com.tapmuncher.gl.GLRenderer

    private val handler = Handler(Looper.getMainLooper())
    private var lastTapUp = 0L
    private var pendingTap: Runnable? = null
    private var downX = 0f
    private var downY = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        sfx = Sfx(this).also { it.loadAsync() }
        game = Game(store, this)
        renderer = com.tapmuncher.gl.GLRenderer(game).also { it.sbs = store.sbs }

        glView = object : GLSurfaceView(this) {}.apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        setContentView(glView)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        game.boot()
    }

    // ------------------------------------------------------------ GameHost

    override fun sfx(id: Int, pitch: Float, vol: Float) = sfx.play(id, pitch, vol)
    override fun sirenStart() = sfx.sirenStart()
    override fun sirenStop() = sfx.sirenStop()
    override fun sirenRate(rate: Float) = sfx.sirenRate(rate)
    override fun frightStart() = sfx.frightStart()
    override fun frightStop() = sfx.frightStop()

    // --------------------------------------------------------------- input

    private fun registerTap() {
        val now = SystemClock.uptimeMillis()
        val gap = now - lastTapUp
        if (gap in 40..320) {
            pendingTap?.let { handler.removeCallbacks(it) }
            pendingTap = null
            lastTapUp = 0
            glView.queueEvent { game.back() }
            return
        }
        lastTapUp = now
        val r = Runnable { pendingTap = null; glView.queueEvent { game.tap() } }
        pendingTap = r
        handler.postDelayed(r, 260)
    }

    private fun swipe(dir: Int) = glView.queueEvent { game.swipe(dir) }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE -> { registerTap(); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { swipe(0); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { swipe(1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { swipe(2); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { swipe(3); return true }
                KeyEvent.KEYCODE_BACK -> { glView.queueEvent { game.back() }; return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Ignore the left temple volume pad.
        if (ev.device?.name?.contains("cyttsp6", ignoreCase = true) == true) return true
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y }
            MotionEvent.ACTION_UP -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                val dead = max(36f, 0.045f * resources.displayMetrics.widthPixels)
                if (abs(dx) < dead && abs(dy) < dead) { registerTap(); return true }
                if (abs(dx) >= abs(dy)) {
                    // horizontal dx sign inverted vs the physical gesture on this pad
                    swipe(if (dx < 0) 3 else 2)
                } else {
                    swipe(if (dy < 0) 0 else 1)
                }
            }
        }
        return true
    }

    // ------------------------------------------------------------ lifecycle

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        glView.onResume()
    }

    override fun onPause() {
        glView.queueEvent { game.onAppPause() }
        sfx.sirenStop()
        sfx.frightStop()
        glView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        sfx.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }
}
