package com.laoxiang.ddz

import android.app.Application
import com.laoxiang.ddz.audio.SoundManager

/**
 * 老乡斗地主 应用入口
 */
class LaoXiangApp : Application() {

    lateinit var sound: SoundManager
        private set

    override fun onCreate() {
        super.onCreate()
        sound = SoundManager(this)
    }
}
