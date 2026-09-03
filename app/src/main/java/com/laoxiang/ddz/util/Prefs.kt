package com.laoxiang.ddz.util

import android.content.Context

/**
 * 本地偏好：昵称 / 头像 / 桌型 / AI 难度 / 声音 / 音乐 / 战绩
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("laoxiang_ddz", Context.MODE_PRIVATE)

    var nickname: String
        get() = sp.getString("nickname", "") ?: ""
        set(v) = sp.edit().putString("nickname", v).apply()

    var avatar: Int
        get() = sp.getInt("avatar", 11)
        set(v) = sp.edit().putInt("avatar", v).apply()

    /** 桌型：round / square */
    var tableStyle: String
        get() = sp.getString("table_style", "square") ?: "square"
        set(v) = sp.edit().putString("table_style", v).apply()

    /** 默认 AI 难度：0/1/2 */
    var aiLevel: Int
        get() = sp.getInt("ai_level", 1)
        set(v) = sp.edit().putInt("ai_level", v).apply()

    var soundEnabled: Boolean
        get() = sp.getBoolean("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    var musicEnabled: Boolean
        get() = sp.getBoolean("music", true)
        set(v) = sp.edit().putBoolean("music", v).apply()

    var wins: Int
        get() = sp.getInt("wins", 0)
        set(v) = sp.edit().putInt("wins", v).apply()

    var losses: Int
        get() = sp.getInt("losses", 0)
        set(v) = sp.edit().putInt("losses", v).apply()

    var beans: Int
        get() = sp.getInt("beans", 10000)
        set(v) = sp.edit().putInt("beans", v).apply()

    fun addResult(won: Boolean, scoreDelta: Int) {
        if (won) wins += 1 else losses += 1
        beans = (beans + scoreDelta).coerceAtLeast(0)
    }
}
