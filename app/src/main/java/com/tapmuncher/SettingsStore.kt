package com.tapmuncher

import android.content.Context
import android.os.Build

/** Persistent options (set on the intro screen) + high score + SBS detection. */
class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("tapmuncher", Context.MODE_PRIVATE)

    private val deviceText = listOf(
        Build.MODEL, Build.MANUFACTURER, Build.BRAND, Build.DEVICE, Build.PRODUCT
    ).joinToString(" ").lowercase()

    // FABLE_X3_STARTER_GUIDE gotcha #24: the X3 Pro reports Build.MODEL=ARGF20;
    // detect RayNeo hardware by manufacturer/brand/product instead.
    val isRayNeoX3 =
        "rayneo" in deviceText || "leiniao" in deviceText || "ffalcon" in deviceText ||
            ("x3" in deviceText && ("tcl" in deviceText || "falcon" in deviceText))

    val sbs get() = isRayNeoX3

    var highScore: Int
        get() = p.getInt("hi", 0)
        set(v) { if (v > highScore) p.edit().putInt("hi", v).apply() }

    /** 0 easy, 1 normal, 2 hard. */
    var difficulty: Int
        get() = p.getInt("diff", 1)
        set(v) { p.edit().putInt("diff", v.coerceIn(0, 2)).apply() }

    /** Maze layout index. */
    var mazeIdx: Int
        get() = p.getInt("maze", 0)
        set(v) { p.edit().putInt("maze", v.coerceIn(0, 1)).apply() }

    /** 0 = 3 lives, 1 = 5 lives. */
    var livesOpt: Int
        get() = p.getInt("lives", 0)
        set(v) { p.edit().putInt("lives", v.coerceIn(0, 1)).apply() }

    var games: Int
        get() = p.getInt("games", 0)
        set(v) { p.edit().putInt("games", v).apply() }
}
