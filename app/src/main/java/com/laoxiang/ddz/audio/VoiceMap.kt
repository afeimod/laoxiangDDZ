package com.laoxiang.ddz.audio

import com.laoxiang.ddz.data.Move
import com.laoxiang.ddz.data.MoveType

/**
 * 出牌牌型 / 快捷喊话 → 语音资源 key（res/raw/voice_*.wav）
 *
 * 语音内容一览（chuichui 音色，24kHz 单声道）：
 *  单牌   三四五六七八九十 / 钩(J) / 圈(Q) / K / 尖(A) / 二 / 小王 / 大王
 *  对子   对三 ~ 对二
 *  三张   三个三 ~ 三个二
 *  牌型   三带一 / 三带二 / 顺子 / 连对 / 飞机 / 四带二 / 四带两对 / 炸弹 / 王炸
 *  喊话   叫地主 / 不叫 / 抢地主 / 不抢
 *  聊天   voice_chat1 ~ voice_chat8（与 QuickChatPanel 的 CHAT_PHRASES 一一对应）
 */
object VoiceMap {

    /** rank → 单牌语音后缀（3..17；王单独命名避免与数字混淆） */
    private val RANK_SUFFIX = mapOf(
        3 to "3", 4 to "4", 5 to "5", 6 to "6", 7 to "7", 8 to "8",
        9 to "9", 10 to "10", 11 to "j", 12 to "q", 13 to "k",
        14 to "a", 15 to "2", 16 to "sjoker", 17 to "bjoker"
    )

    /**
     * 出牌牌型 → 语音 key（null = 无专属语音，退回普通出牌音效）
     * 注意：单王不走 voice_c 前缀——资源名为 voice_sjoker / voice_bjoker（v13 修复）
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
            MoveType.PLANE, MoveType.PLANE_SINGLE, MoveType.PLANE_PAIR -> "voice_feiji"
            MoveType.FOUR_TWO_SINGLE -> "voice_sidai2"
            MoveType.FOUR_TWO_PAIR -> "voice_sidai2p"
            MoveType.BOMB -> "voice_zhadan"
            MoveType.ROCKET -> "voice_wangzha"
        }
    }

    /** 快捷喊话序号（1..8）→ 语音 key */
    fun forChat(code: Int): String? = if (code in 1..8) "voice_chat$code" else null

    /** SoundManager 启动时批量注册的全部语音资源名 */
    val ALL: List<String> = buildList {
        addAll(
            listOf("3", "4", "5", "6", "7", "8", "9", "10", "j", "q", "k", "a", "2")
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
                "voice_zhadan", "voice_wangzha",
                "voice_jiao", "voice_bujiao", "voice_qiangd", "voice_buqiangd"
            )
        )
        addAll((1..8).map { "voice_chat$it" })
    }
}
