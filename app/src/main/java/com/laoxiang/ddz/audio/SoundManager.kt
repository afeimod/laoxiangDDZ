package com.laoxiang.ddz.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.laoxiang.ddz.R

/**
 * 音效管理：SoundPool 短音效 + MediaPlayer 循环 BGM
 *
 * 素材映射（res/raw）：
 *  sfx_play       出牌（合成"啪"声）
 *  sfx_select     选牌（合成"嗒"声）
 *  sfx_shuffle    洗牌
 *  sfx_jiaofen    叫地主语音
 *  sfx_qiang      抢地主语音
 *  sfx_buqiang    不抢语音
 *  sfx_pass       要不起语音
 *  sfx_bomb       普通炸弹（闷响爆炸）
 *  sfx_wangzha    王炸（双王火箭）专用
 *  sfx_deal       发牌单张"啩嗒"声
 *  sfx_plane      飞机
 *  sfx_win        赢牌
 *  sfx_lose       输牌
 *  sfx_kuaidian   "快点吧我等到花儿都谢了"
 *  bgm_game       对局背景乐（循环）
 *
 *  voice_*        v12 牌型播报/叫抢/快捷喊话语音（62 条，经 VoiceMap 批量注册，
 *                 资源名动态查找，release 压缩需 keep.xml 白名单 @raw/voice_*）
 */
class SoundManager(private val context: Context) {

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<String, Int>()
    private var bgm: MediaPlayer? = null
    @Volatile
    var soundEnabled = true
    @Volatile
    var musicEnabled = true

    init {
        ids["play"] = pool.load(context, R.raw.sfx_play, 1)
        ids["select"] = pool.load(context, R.raw.sfx_select, 1)
        ids["shuffle"] = pool.load(context, R.raw.sfx_shuffle, 1)
        ids["jiaofen"] = pool.load(context, R.raw.sfx_jiaofen, 1)
        ids["qiang"] = pool.load(context, R.raw.sfx_qiang, 1)
        ids["buqiang"] = pool.load(context, R.raw.sfx_buqiang, 1)
        ids["pass"] = pool.load(context, R.raw.sfx_pass, 1)
        ids["bomb"] = pool.load(context, R.raw.sfx_bomb, 1)
        ids["wangzha"] = pool.load(context, R.raw.sfx_wangzha, 1)
        ids["deal"] = pool.load(context, R.raw.sfx_deal, 1)
        ids["plane"] = pool.load(context, R.raw.sfx_plane, 1)
        ids["win"] = pool.load(context, R.raw.sfx_win, 1)
        ids["lose"] = pool.load(context, R.raw.sfx_lose, 1)
        ids["kuaidian"] = pool.load(context, R.raw.sfx_kuaidian, 1)

        // 出牌牌型/叫抢/快捷喊话语音（voice_*，共 62 条，批量注册）
        VoiceMap.ALL.forEach { name ->
            val id = context.resources.getIdentifier(name, "raw", context.packageName)
            if (id != 0) ids[name] = pool.load(context, id, 1)
        }
    }

    fun play(key: String, volume: Float = 1f) {
        if (!soundEnabled) return
        val id = ids[key] ?: return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    // ------------------------------------------------ BGM

    fun startBgm() {
        if (!musicEnabled) return
        stopBgm()
        try {
            bgm = MediaPlayer.create(context, R.raw.bgm_game)?.apply {
                isLooping = true
                setVolume(0.35f, 0.35f)
                start()
            }
        } catch (_: Exception) {
        }
    }

    fun stopBgm() {
        try {
            bgm?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (_: Exception) {
        }
        bgm = null
    }

    fun release() {
        stopBgm()
        pool.release()
    }
}
