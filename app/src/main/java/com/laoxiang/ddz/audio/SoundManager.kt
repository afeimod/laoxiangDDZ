package com.laoxiang.ddz.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.laoxiang.ddz.R
import kotlin.random.Random

/**
 * 音效管理：SoundPool 短音效 + MediaPlayer 循环 BGM
 *
 * v1.6.0：新增「素材包」体系（pack）——按界面切换到参考 APK 的音效/语音：
 *  pack = "ddz"   斗地主参考 APK（斗地主_5.3.29）：bg1-3 音乐、chupai/dealcards/
 *                 zhadan/rocket/feiji/spring/win/lose 音效、man/woman 全套叫抢/报牌语音
 *  pack = "mj"    麻将参考 APK（麻将世界/血战麻将）：xzhallBGM、摸牌/出牌/洗牌/骰子
 *                 音效、normal_t/b/w 万条筒报牌、xz_local 四川话 胡/碰/杠/花猪 语音
 *  pack = "default" 项目原有 TTS/合成音效（其它玩法与兜底）
 *
 * 素材映射（res/raw）：
 *  —— 斗地主参考 APK ——
 *  ddz_bgm_1/2/3          大厅/对局 BGM（bg1-3.mp3）
 *  ddz_sfx_button/chupai/dealcards/feiji/lose/rocket/spring/win/xuanpai/ya/zhadan/baojing
 *  ddz_vm_* / ddz_vw_*    男/女声：3..17（点数）、jdz1-6（叫地主）、buyao1-4（不要）、
 *                         pair3-15（对子）、cardtype3-14（单张）、baojing1-2、jb1-3
 *  —— 麻将参考 APK ——
 *  mjw_bgm_hall / mjw_bgm_game      大厅 / 血战 BGM
 *  mjw_sfx_dice/dapai/fapai/mopai/lipai/xipai/tuipai/qipai/daojishi/
 *          huangzhuang/liangpai/tuoguan/jctuoguan/zimoying/fpaoying/fpaoshu/
 *          disanjia/sanjiashu/button/close
 *  mjw_v_normal_t/b/w_0/1_1..9      普通话报牌（筒/条/万 × 两种音色）
 *  mjw_v_local_w_*                  本地话报牌
 *  mjw_v_xz_normal_hu_0..2          胡（普通话）
 *  mjw_v_xz_local_hu/peng/gang_gf/gang_xy/pao_two/pao_three/huazhu/tg_0/1
 *                                   四川话动作语音
 *  mjw_v_c_guafeng/he1-3/huazhu/shu/xiayu.wav  花猪/刮风/下雨/点炮 音效
 *
 *  —— 项目原有 ——
 *  sfx_play/select/shuffle/jiaofen/qiang/buqiang/pass/bomb/wangzha/deal/plane/
 *  win/lose/kuaidian, bgm_game/bgm_mahjong, voice_*（62 条 TTS）
 */
class SoundManager(private val context: Context) {

    companion object {
        /** 当前素材包：由各界面 DisposableEffect 设置/恢复 */
        const val PACK_DEFAULT = "default"
        const val PACK_DDZ = "ddz"
        const val PACK_MJ = "mj"
    }

    private val pool: SoundPool = SoundPool.Builder()
        .setMaxStreams(5)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val ids = HashMap<String, Int>()
    private val lazyIds = HashMap<String, Int>()
    private var bgm: MediaPlayer? = null
    private var bgmKey: String = ""
    @Volatile
    var soundEnabled = true
    @Volatile
    var musicEnabled = true
    @Volatile
    var pack: String = PACK_DEFAULT

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

        // 出牌牌型/叫抢/快捷喊话语音（voice_*，批量注册）
        VoiceMap.ALL.forEach { name ->
            val id = context.resources.getIdentifier(name, "raw", context.packageName)
            if (id != 0) ids[name] = pool.load(context, id, 1)
        }
    }

    // ------------------------------------------------ 关键映射（素材包替换）

    /**
     * 语音音色：整局固定一次（v1.6.0 反馈修复：此前每次播放都随机男/女，
     * 同一句牌连着播会男女声乱跳 —— 改为进程内固定音色，贴近参考 APK 单音色播报）
     */
    private val voiceGender: String = if (Random.nextBoolean()) "vm" else "vw"

    /** 斗地主参考 APK：把项目语音 key 换成 apk 固定音色语音 */
    private fun ddzVoiceOf(key: String): String? {
        val g = voiceGender
        return when {
            // 单张点数：3..10/j/q/k/a/2/双王 → apk 3..17
            key.startsWith("voice_c") -> {
                val n = key.removePrefix("voice_c")
                val p = when (n) {
                    "j" -> 11; "q" -> 12; "k" -> 13; "a" -> 14; "2" -> 15
                    else -> n.toIntOrNull() ?: return null
                }
                if (p in 3..15) "ddz_${g}_$p" else null
            }
            key == "voice_sjoker" -> "ddz_${g}_16"
            key == "voice_bjoker" -> "ddz_${g}_17"
            key.startsWith("voice_p") -> {
                val n = key.removePrefix("voice_p").toIntOrNull() ?: return null
                if (n in 3..15) "ddz_${g}_pair$n" else null
            }
            // 牌型播报（三带一/顺子/连对…）→ apk 同名牌型语音（此前漏映射，TTS 与 apk 声混播）
            key.startsWith("voice_cardtype") -> {
                val n = key.removePrefix("voice_cardtype").toIntOrNull() ?: return null
                if (n in 3..14 && n != 9 && n != 10) "ddz_${g}_cardtype$n" else null
            }
            key == "voice_jiao" || key == "voice_qiangd" -> "ddz_${g}_jdz${Random.nextInt(1, 7)}"
            key == "voice_bujiao" || key == "voice_buqiangd" -> "ddz_${g}_buyao${Random.nextInt(1, 5)}"
            key == "pass" -> "ddz_${g}_buyao${Random.nextInt(1, 5)}"
            else -> null
        }
    }

    /** 麻将参考 APK：项目麻将语音 key → apk 报牌/动作语音 */
    private fun mjVoiceOf(key: String): String? {
        val v = Random.nextInt(0, 2)   // 两种音色随机
        return when {
            key.startsWith("voice_mj_w") -> "mjw_v_normal_w_${v}_${key.removePrefix("voice_mj_w")}"
            key.startsWith("voice_mj_t") -> "mjw_v_normal_t_${v}_${key.removePrefix("voice_mj_t")}"
            key.startsWith("voice_mj_b") -> "mjw_v_normal_b_${v}_${key.removePrefix("voice_mj_b")}"
            key == "voice_mj_peng" -> "mjw_v_xz_local_peng_$v"
            key == "voice_mj_gang" -> "mjw_v_xz_local_gang_gf_$v"
            key == "voice_mj_hu" -> "mjw_v_xz_normal_hu_${Random.nextInt(0, 3)}"
            key.startsWith("voice_mj_fan_qg") -> "mjw_v_xz_local_hu_$v"
            else -> null
        }
    }

    /** 音效 key → 素材包替换 key（null = 原样） */
    private fun remap(key: String): String {
        return when (pack) {
            PACK_DDZ -> when (key) {
                "play" -> "ddz_sfx_chupai"
                "select" -> "ddz_sfx_xuanpai"
                "shuffle" -> "ddz_sfx_dealcards"          // 整段发牌音（参考 APK，一局一次）
                // ⚠发牌逐张点击音：不能用 4.3s 的 dealcards（GameScreen 每张 65ms 一响，
                //   v1.6.0 初版误映射成长音，开局变成十几条 4.3s 叠音）—— 用裁剪短音
                "deal" -> "ddz_sfx_deal"
                "bomb" -> "ddz_sfx_zhadan"
                "wangzha" -> "ddz_sfx_rocket"
                "plane" -> "ddz_sfx_feiji"
                "win" -> "ddz_sfx_win"
                "lose" -> "ddz_sfx_lose"
                "spring" -> "ddz_sfx_spring"               // 春天（此前未接逻辑）
                "baojing" -> "ddz_sfx_baojing"             // 剩两张牌报警（此前未接逻辑）
                "button" -> "ddz_sfx_button"               // 按钮（此前未接逻辑）
                else -> ddzVoiceOf(key) ?: key
            }
            PACK_MJ -> when (key) {
                "play" -> "mjw_sfx_dapai"
                "select" -> "mjw_sfx_lipai"
                "shuffle" -> "mjw_sfx_xipai"
                "deal" -> "mjw_sfx_fapai"
                "dice" -> "mjw_sfx_dice"
                "button" -> "mjw_sfx_button"
                else -> mjVoiceOf(key) ?: key
            }
            else -> key
        }
    }

    fun play(key: String, volume: Float = 1f) {
        if (!soundEnabled) return
        val actual = remap(key)
        val id = ids[actual] ?: lazyIds[actual] ?: loadLazy(actual) ?: run {
            // 素材包缺失时退回原 key（保证永不出声）
            if (actual != key) return playFallback(key, volume)
            return
        }
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    private fun playFallback(key: String, volume: Float) {
        val id = ids[key] ?: return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    /** 直接播素材包原始音效（各界面专用，如骰子/开局/杠上花） */
    fun playRaw(name: String, volume: Float = 1f) {
        if (!soundEnabled) return
        val id = ids[name] ?: lazyIds[name] ?: loadLazy(name) ?: return
        pool.play(id, volume, volume, 1, 0, 1f)
    }

    private fun loadLazy(name: String): Int? {
        if (lazyIds.containsKey(name)) return lazyIds[name]
        if (!name.matches(Regex("[a-z0-9_]+"))) return null
        val resId = context.resources.getIdentifier(name, "raw", context.packageName)
        if (resId == 0) {
            lazyIds[name] = 0
            return null
        }
        val sid = pool.load(context, resId, 1)
        lazyIds[name] = sid
        return sid
    }

    // ------------------------------------------------ BGM

    /** 按 pack 起背景乐：ddz→斗地主 bg2；mj→血战 BGM；default→原 bgm(_mahjong) */
    fun startBgm(mahjong: Boolean = false, key: String? = null) {
        if (!musicEnabled) return
        val want = key ?: when (pack) {
            PACK_DDZ -> "ddz_bgm_2"
            PACK_MJ -> "mjw_bgm_game"
            PACK_DEFAULT -> if (mahjong) "bgm_mahjong" else "bgm_game"
            else -> "bgm_game"
        }
        if (want == bgmKey && bgm?.isPlaying == true) return
        stopBgm()
        val resId = when (want) {
            "ddz_bgm_1" -> R.raw.ddz_bgm_1
            "ddz_bgm_2" -> R.raw.ddz_bgm_2
            "ddz_bgm_3" -> R.raw.ddz_bgm_3
            "mjw_bgm_hall" -> R.raw.mjw_bgm_hall
            "mjw_bgm_game" -> R.raw.mjw_bgm_game
            "bgm_mahjong" -> R.raw.bgm_mahjong
            else -> R.raw.bgm_game
        }
        try {
            bgm = MediaPlayer.create(context, resId)?.apply {
                isLooping = true
                setVolume(0.35f, 0.35f)
                start()
            }
            bgmKey = want
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
        bgmKey = ""
    }

    fun release() {
        stopBgm()
        pool.release()
    }
}
