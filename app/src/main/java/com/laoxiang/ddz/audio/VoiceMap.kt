package com.laoxiang.ddz.audio

import com.laoxiang.ddz.data.BtMove
import com.laoxiang.ddz.data.BtType
import com.laoxiang.ddz.data.GdMove
import com.laoxiang.ddz.data.GdType
import com.laoxiang.ddz.data.MjTile
import com.laoxiang.ddz.data.Move
import com.laoxiang.ddz.data.MoveType
import com.laoxiang.ddz.data.SjPlay
import com.laoxiang.ddz.data.SjType

/**
 * 出牌牌型 / 快捷喊话 → 语音资源 key（res/raw/voice_*.wav）
 *
 * 语音内容一览（chuichui 音色，24kHz 单声道）：
 *  单牌   三四五六七八九十 / 钩(J) / 圈(Q) / K / 尖(A) / 二 / 小王 / 大王
 *  对子   对三 ~ 对二
 *  三张   三个三 ~ 三个二
 *  牌型   三带一 / 三带二 / 顺子 / 连对 / 飞机 / 四带二 / 四带两对
 *         炸弹(重语气) / 王炸(重语气) / 飞机(重语气)
 *  喊话   叫地主(高亢) / 不叫(低落) / 抢地主(急切) / 不抢(平静) —— 语气化
 *  聊天   voice_chat1 ~ voice_chat8（催促/决战/叹气 3 条语气重录）
 *  新游戏 同花 / 葫芦 / 铁支 / 同花顺 / 拖拉机 / 钢板 / 木板 / 天王炸
 */
object VoiceMap {

    /** rank → 单牌语音后缀（2..17；王单独命名避免与数字混淆） */
    private val RANK_SUFFIX = mapOf(
        2 to "2", 3 to "3", 4 to "4", 5 to "5", 6 to "6", 7 to "7", 8 to "8",
        9 to "9", 10 to "10", 11 to "j", 12 to "q", 13 to "k",
        14 to "a", 15 to "2", 16 to "sjoker", 17 to "bjoker"
    )

    /**
     * 斗地主出牌牌型 → 语音 key（null = 无专属语音，退回普通出牌音效）
     * 特殊牌型（炸弹/王炸/飞机）走"加重语气"版本
     */
    fun forMove(move: Move?): String? {
        move ?: return null
        return when (move.type) {
            MoveType.SINGLE -> when (move.mainRank) {
                16 -> "voice_sjoker"
                17 -> "voice_bjoker"
                else -> "voice_c" + (RANK_SUFFIX[move.mainRank] ?: move.mainRank.toString())
            }
            MoveType.PAIR -> "voice_p" + move.mainRank
            MoveType.TRIO -> "voice_t" + move.mainRank
            MoveType.TRIO_SINGLE -> "voice_sandai1"
            MoveType.TRIO_PAIR -> "voice_sandai2"
            MoveType.STRAIGHT -> "voice_shunzi"
            MoveType.PAIR_STRAIGHT -> "voice_liandui"
            MoveType.PLANE, MoveType.PLANE_SINGLE, MoveType.PLANE_PAIR -> "voice_feiji_strong"
            MoveType.FOUR_TWO_SINGLE -> "voice_sidai2"
            MoveType.FOUR_TWO_PAIR -> "voice_sidai2p"
            MoveType.BOMB -> "voice_zhadan_strong"
            MoveType.ROCKET -> "voice_wangzha_strong"
        }
    }

    /** 锄大地牌型 → 语音 key（无王） */
    fun forBigTwo(move: BtMove?): String? {
        move ?: return null
        return when (move.type) {
            BtType.SINGLE -> "voice_c" + move.mainRank.coerceAtMost(15)
            BtType.PAIR -> "voice_p" + move.mainRank
            BtType.TRIO -> "voice_t" + move.mainRank
            BtType.STRAIGHT -> "voice_shunzi"
            BtType.FLUSH -> "voice_tonghua"
            BtType.FULLHOUSE -> "voice_hulu"
            BtType.QUAD -> "voice_tiezhi"
            BtType.STRAIGHT_FLUSH -> "voice_tonghuashun"
        }
    }

    /** 掼蛋牌型 → 语音 key（[levelRank] 用于级牌单张按点数播报） */
    fun forGuandan(move: GdMove?, levelRank: Int): String? {
        move ?: return null
        return when (move.type) {
            GdType.SINGLE -> when (move.mainPower) {
                17 -> "voice_sjoker"
                18 -> "voice_bjoker"
                16 -> "voice_c" + (RANK_SUFFIX[levelRank] ?: "$levelRank")   // 级牌按点数播报
                else -> "voice_c" + (RANK_SUFFIX[move.mainPower] ?: "${move.mainPower}")
            }
            GdType.PAIR -> when {
                move.mainPower == 16 -> "voice_p$levelRank"      // 级牌对按点数播报
                move.mainPower >= 17 -> null                     // 王对：无专属语音
                else -> "voice_p${move.mainPower}"
            }
            GdType.TRIO -> if (move.mainPower == 16) "voice_t$levelRank" else "voice_t${move.mainPower}"
            GdType.TRIO_PAIR -> "voice_sandai2"
            GdType.BANZI -> "voice_banzi"
            GdType.GANGBAN -> "voice_gangban"
            GdType.STRAIGHT -> "voice_shunzi"
            GdType.BOMB -> "voice_zhadan_strong"
            GdType.STRAIGHT_FLUSH -> "voice_tonghuashun"
            GdType.ROCKET -> "voice_tianwangzha"
        }
    }

    /** 升级牌型 → 语音 key（级牌/主10 单张按原点播报） */
    fun forShengji(play: SjPlay?, levelRank: Int, trumpSuitLabelRank: Int = -1): String? {
        play ?: return null
        return when (play.type) {
            SjType.SINGLE -> {
                val c = play.cards.first()
                when {
                    c.rank == 16 -> "voice_sjoker"
                    c.rank == 17 -> "voice_bjoker"
                    else -> "voice_c" + (RANK_SUFFIX[c.rank] ?: "${c.rank}")
                }
            }
            SjType.PAIR -> {
                val c = play.cards.first()
                "voice_p" + c.rank
            }
            SjType.TRACTOR -> "voice_tuolaji"
            SjType.THROW -> null
        }
    }

    /** 快捷喊话序号（1..8）→ 语音 key */
    fun forChat(code: Int): String? = if (code in 1..8) "voice_chat$code" else null

    // ================================ 麻将（v22）

    /** 麻将牌张 → 语音 key（女声：一万~九万/一筒~九筒/一条~九条/东南西北/红中发财白板） */
    fun forMjTile(t: MjTile): String = when (t.suit) {
        com.laoxiang.ddz.data.MjSuit.WAN -> "voice_mj_w${t.num}"
        com.laoxiang.ddz.data.MjSuit.TONG -> "voice_mj_t${t.num}"
        com.laoxiang.ddz.data.MjSuit.TIAO -> "voice_mj_b${t.num}"
        com.laoxiang.ddz.data.MjSuit.ZI -> when (t.num) {
            1 -> "voice_mj_f1"; 2 -> "voice_mj_f2"; 3 -> "voice_mj_f3"; 4 -> "voice_mj_f4"
            5 -> "voice_mj_zh"; 6 -> "voice_mj_fa"; else -> "voice_mj_ba"
        }
    }

    /** 麻将宣告动作 → 语音 key（男声：吃/碰/杠/胡了） */
    fun forMjAction(kind: String): String? = when (kind) {
        "CHI" -> "voice_mj_chi"
        "PENG" -> "voice_mj_peng"
        "GANG" -> "voice_mj_gang"
        "HU" -> "voice_mj_hu"
        else -> null
    }

    /** 麻将番型 → 语音 key（男声报番） */
    fun forMjFan(fan: String): String? = when {
        fan.startsWith("清一色") -> "voice_mj_fan_qing"
        fan.startsWith("混一色") -> "voice_mj_fan_hun"
        fan.startsWith("对对") -> "voice_mj_fan_dd"
        fan.startsWith("豪华七对") || fan.startsWith("龙七对") -> "voice_mj_fan_l7"
        fan.startsWith("七对") -> "voice_mj_fan_7"
        fan.startsWith("十三幺") -> "voice_mj_fan_13"
        fan.startsWith("杠上") -> "voice_mj_fan_gs"
        fan.startsWith("海底") -> "voice_mj_fan_hd"
        fan.startsWith("抢杠") -> "voice_mj_fan_qg"
        fan.startsWith("大三元") -> "voice_mj_fan_dy"
        fan.startsWith("小三元") -> "voice_mj_fan_xy"
        fan.startsWith("金钩钩") -> "voice_mj_fan_jgg"
        fan.startsWith("十八罗汉") -> "voice_mj_fan_sl"
        fan.startsWith("天胡") -> "voice_mj_fan_th"
        fan.startsWith("字一色") -> "voice_mj_fan_zys"
        fan.startsWith("根") -> "voice_mj_fan_gen"
        else -> null
    }

    /** SoundManager 启动时批量注册的全部语音资源名 */
    val ALL: List<String> = buildList {
        addAll(
            listOf("2", "3", "4", "5", "6", "7", "8", "9", "10", "j", "q", "k", "a")
                .map { "voice_c$it" }
        )
        add("voice_sjoker")
        add("voice_bjoker")
        addAll((3..15).map { "voice_p$it" })
        addAll((3..15).map { "voice_t$it" })
        addAll(
            listOf(
                "voice_sandai1", "voice_sandai2", "voice_shunzi", "voice_liandui",
                "voice_feiji", "voice_sidai2", "voice_sidai2p",
                "voice_jiao", "voice_bujiao", "voice_qiangd", "voice_buqiangd",
                // v14 重语气牌型
                "voice_zhadan_strong", "voice_wangzha_strong", "voice_feiji_strong",
                // v14 新游戏牌型
                "voice_tonghua", "voice_hulu", "voice_tiezhi", "voice_tonghuashun",
                "voice_tuolaji", "voice_gangban", "voice_banzi", "voice_tianwangzha"
            )
        )
        addAll((1..8).map { "voice_chat$it" })
        // 麻将（v22）：27 张牌 + 动作 + 番型
        addAll((1..9).map { "voice_mj_w$it" })
        addAll((1..9).map { "voice_mj_t$it" })
        addAll((1..9).map { "voice_mj_b$it" })
        addAll((1..4).map { "voice_mj_f$it" })
        addAll(
            listOf(
                "voice_mj_zh", "voice_mj_fa", "voice_mj_ba",
                "voice_mj_chi", "voice_mj_peng", "voice_mj_gang", "voice_mj_hu",
                "voice_mj_zimo", "voice_mj_guo", "voice_mj_dingque", "voice_mj_huan3",
                "voice_mj_liuju", "voice_mj_ting",
                "voice_mj_fan_qing", "voice_mj_fan_hun", "voice_mj_fan_dd", "voice_mj_fan_7",
                "voice_mj_fan_l7", "voice_mj_fan_13", "voice_mj_fan_gs", "voice_mj_fan_hd",
                "voice_mj_fan_qg", "voice_mj_fan_dy", "voice_mj_fan_xy", "voice_mj_fan_jgg",
                "voice_mj_fan_sl", "voice_mj_fan_th", "voice_mj_fan_zys", "voice_mj_fan_gen"
            )
        )
    }
}
