package com.tapmuncher.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import com.tapmuncher.R
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mostly synthesized SFX bank for TapMuncher. The classic kit is remixed with
 * an alternating two-note WAKA, a rising SIREN loop, a bubbling FRIGHT loop,
 * and a licensed 8-bit MP3 jingle at the start of every level.
 */
class Sfx(private val context: Context) {

    companion object {
        const val WAKA1 = 0
        const val WAKA2 = 1
        const val POWER = 2
        const val GHOST = 3        // pursuer swallowed
        const val DEATH = 4
        const val FRUIT = 5
        const val EXTRA = 6
        const val READY = 7
        const val CLEARJ = 8
        const val GAMEOVER = 9
        const val UI = 10
        const val SELECT = 11
        const val BACK = 12
        const val REVIVE = 13      // eyes reach home and re-form
        const val SIREN = 14       // loop
        const val FRIGHT = 15      // loop
        private const val COUNT = 16
        private const val RATE = 22050
    }

    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        ).build()

    private val ids = IntArray(COUNT)
    @Volatile private var loaded = false
    @Volatile private var readyLoaded = false
    private var readyPending = false
    private var readyPendingPitch = 1f
    private var readyPendingVolume = 1f
    @Volatile var volume = 0.7f
    private var sirenStream = 0
    private var frightStream = 0
    @Volatile private var sirenRateWanted = 0.8f
    private val rng = Random(6)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            handler?.post {
                if (sampleId != ids[READY]) return@post
                readyLoaded = status == 0
                if (readyLoaded && readyPending) {
                    readyPending = false
                    playLoaded(READY, readyPendingPitch, readyPendingVolume)
                }
            }
        }
    }

    fun loadAsync() {
        thread = HandlerThread("muncher-sfx").apply { start() }
        handler = Handler(thread!!.looper)
        handler?.post {
            runCatching {
                val dir = File(context.cacheDir, "sfx").apply { mkdirs() }
                ids[WAKA1] = load(dir, "wk1", buf(70) { t -> sq(300f + 260f * (t / 0.07f), t) * exp(-t * 18f) * 0.4f })
                ids[WAKA2] = load(dir, "wk2", buf(70) { t -> sq(560f - 260f * (t / 0.07f), t) * exp(-t * 18f) * 0.4f })
                ids[POWER] = load(dir, "pow", buf(340) { t -> (sine(240f + 500f * t, t) * 0.5f + sq(120f + 250f * t, t) * 0.15f) * exp(-t * 5f) })
                ids[GHOST] = load(dir, "gho", arpeggio(intArrayOf(392, 659, 1046), 55, 0.7f))
                ids[DEATH] = load(dir, "die", buf(1100) { t ->
                    val f = 700f - t * 560f
                    (sine(f, t) * 0.5f + saw(f * 0.5f, t) * 0.25f) * exp(-t * 2.2f)
                })
                ids[FRUIT] = load(dir, "fru", arpeggio(intArrayOf(659, 880, 1318), 50, 0.7f))
                ids[EXTRA] = load(dir, "ext", arpeggio(intArrayOf(784, 1046, 1318, 1568, 2093), 65, 0.75f))
                ids[READY] = pool.load(context, R.raw.level_start, 1)
                ids[CLEARJ] = load(dir, "clr", arpeggio(intArrayOf(523, 659, 784, 1046, 1318), 75, 0.72f))
                ids[GAMEOVER] = load(dir, "ovr", buf(900) { t ->
                    val f = if (t < 0.4f) 320f - t * 160f else 260f - (t - 0.4f) * 130f
                    (saw(f, t) * 0.4f + sine(f * 0.5f, t) * 0.4f) * exp(-t * 2.1f)
                })
                ids[UI] = load(dir, "ui", buf(35) { t -> sine(950f, t) * exp(-t * 60f) * 0.5f })
                ids[SELECT] = load(dir, "sel", buf(100) { t -> sine(680f + 500f * t, t) * exp(-t * 14f) * 0.5f })
                ids[BACK] = load(dir, "bak", buf(100) { t -> sine(540f - 240f * t, t) * exp(-t * 14f) * 0.5f })
                ids[REVIVE] = load(dir, "rev", buf(220) { t -> sine(400f + 700f * t, t) * exp(-t * 8f) * 0.45f })
                // siren: two-tone wail, loopable; the game sweeps its rate up
                ids[SIREN] = load(dir, "sir", buf(700) { t ->
                    val f = 420f + 160f * sin(2f * PI.toFloat() * 1.43f * t)
                    (sine(f, t) * 0.4f + sine(f * 0.5f, t) * 0.15f) * 0.8f
                })
                // frightened: nervous bubbling
                ids[FRIGHT] = load(dir, "fri", buf(500) { t ->
                    val f = 180f + 120f * ((t * 8f) % 1f)
                    (sq(f, t) * 0.3f + sine(f * 2f, t) * 0.2f) * 0.8f
                })
                loaded = true
                if (sirenPending) { sirenPending = false; reallyStartSiren() }
            }
        }
    }

    fun play(id: Int, pitch: Float = 1f, vol: Float = 1f) {
        if (id < 0 || id >= COUNT) return
        handler?.post {
            if (!loaded) return@post
            if (id == READY && !readyLoaded) {
                readyPending = true
                readyPendingPitch = pitch
                readyPendingVolume = vol
                return@post
            }
            playLoaded(id, pitch, vol)
        }
    }

    private fun playLoaded(id: Int, pitch: Float, vol: Float) {
        val s = ids[id]
        if (s == 0) return
        val v = (volume * vol).coerceIn(0f, 1f)
        if (v <= 0f) return
        pool.play(s, v, v, 1, 0, pitch.coerceIn(0.5f, 2f))
    }

    @Volatile private var sirenPending = false

    fun sirenStart() {
        handler?.post {
            if (!loaded) { sirenPending = true; return@post }
            reallyStartSiren()
        }
    }

    private fun reallyStartSiren() {
        if (sirenStream != 0) return
        val v = (volume * 0.30f).coerceIn(0f, 1f)
        sirenStream = pool.play(ids[SIREN], v, v, 0, -1, sirenRateWanted.coerceIn(0.5f, 2f))
    }

    fun sirenRate(rate: Float) {
        sirenRateWanted = rate
        handler?.post { if (sirenStream != 0) pool.setRate(sirenStream, rate.coerceIn(0.5f, 2f)) }
    }

    fun sirenStop() {
        handler?.post {
            sirenPending = false
            if (sirenStream != 0) { pool.stop(sirenStream); sirenStream = 0 }
        }
    }

    fun frightStart() {
        handler?.post {
            if (!loaded || frightStream != 0) return@post
            val v = (volume * 0.32f).coerceIn(0f, 1f)
            frightStream = pool.play(ids[FRIGHT], v, v, 0, -1, 1f)
        }
    }

    fun frightStop() {
        handler?.post { if (frightStream != 0) { pool.stop(frightStream); frightStream = 0 } }
    }

    fun release() {
        handler?.post { runCatching { pool.release() } }
        thread?.quitSafely()
        thread = null
        handler = null
    }

    // ------------------------------------------------------------ synthesis

    private fun buf(ms: Int, gen: (Float) -> Float): ShortArray {
        val n = RATE * ms / 1000
        return ShortArray(n) { i -> (gen(i.toFloat() / RATE).coerceIn(-1f, 1f) * 30000f).toInt().toShort() }
    }

    private fun sine(f: Float, t: Float) = sin(2.0 * PI * f * t).toFloat()
    private fun saw(f: Float, t: Float): Float { val p = (f * t) % 1f; return 2f * p - 1f }
    private fun sq(f: Float, t: Float) = if ((f * t) % 1f < 0.5f) 1f else -1f
    private fun noise() = rng.nextFloat() * 2f - 1f

    private fun arpeggio(freqs: IntArray, noteMs: Int, amp: Float): ShortArray {
        val total = noteMs * freqs.size + 220
        return buf(total) { t ->
            var v = 0f
            for ((i, f) in freqs.withIndex()) {
                val start = i * noteMs / 1000f
                if (t >= start) {
                    val lt = t - start
                    v += (sine(f.toFloat(), lt) + 0.3f * sine(f * 2f, lt)) * exp(-lt * 5.5f) * amp * 0.4f
                }
            }
            v
        }
    }

    // ------------------------------------------------------------- wav

    private fun DataOutputStream.wInt(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF) }
    private fun DataOutputStream.wShort(v: Int) { write(v and 0xFF); write((v shr 8) and 0xFF) }

    private fun load(dir: File, name: String, pcm: ShortArray): Int {
        val f = File(dir, "$name.wav")
        val dataLen = pcm.size * 2
        DataOutputStream(BufferedOutputStream(FileOutputStream(f))).use { o ->
            o.writeBytes("RIFF"); o.wInt(36 + dataLen); o.writeBytes("WAVE")
            o.writeBytes("fmt "); o.wInt(16); o.wShort(1); o.wShort(1)
            o.wInt(RATE); o.wInt(RATE * 2); o.wShort(2); o.wShort(16)
            o.writeBytes("data"); o.wInt(dataLen)
            for (s in pcm) o.wShort(s.toInt())
        }
        return pool.load(f.absolutePath, 1)
    }
}
