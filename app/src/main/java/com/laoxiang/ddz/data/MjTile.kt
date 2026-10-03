package com.laoxiang.ddz.data

/**
 * 麻将数据模型（纯 Kotlin，无 Android 依赖，可单元测试）
 *
 * 136 张标准麻将（万/筒/条 各 1-9 ×4 + 东南西北中发白 ×4）；
 * 四川模式仅用前 108 张（万/筒/条）。
 *
 * id 编码（与牌面一一对应，稳定可序列化）：
 *  万 0..35   （num 1..9 × 4 张）
 *  筒 36..71
 *  条 72..107
 *  字 108..135（东南西北中发白 各 4 张）
 *
 * code 0..33 用于胡牌/算番判定：
 *  万 0..8、筒 9..17、条 18..26、字 27..33（东南西北中发白）
 */
@kotlinx.serialization.Serializable
enum class MjSuit(val label: String) {
    WAN("万"), TONG("筒"), TIAO("条"), ZI("字");

    /** 四川模式：仅万筒条三门 */
    val isNumber: Boolean get() = this != ZI
}

@kotlinx.serialization.Serializable
data class MjTile(
    val id: Int,
    val suit: MjSuit,
    /** 数牌 1..9；字牌 1..7 = 东南西北中发白 */
    val num: Int
) {
    /** 胡牌判定用 0..33 连续编码 */
    val code: Int get() = when (suit) {
        MjSuit.WAN -> num - 1
        MjSuit.TONG -> 9 + num - 1
        MjSuit.TIAO -> 18 + num - 1
        MjSuit.ZI -> 27 + num - 1
    }

    val isZi: Boolean get() = suit == MjSuit.ZI

    /** 幺九（数牌 1/9）；字牌不算幺九 */
    val isYaoJiu: Boolean get() = suit.isNumber && (num == 1 || num == 9)

    /** 单字编号：东南西北中发白 */
    val ziLabel: String get() = when (num) {
        1 -> "東"; 2 -> "南"; 3 -> "西"; 4 -> "北"; 5 -> "中"; 6 -> "發"; 7 -> "白"
        else -> "?"
    }

    /** 中文全名：五万 / 三筒 / 七条 / 红中 / 发财 / 白板 / 东风 */
    val label: String
        get() = when (suit) {
            MjSuit.WAN -> numCn + "万"
            MjSuit.TONG -> numCn + "筒"
            MjSuit.TIAO -> numCn + "条"
            MjSuit.ZI -> when (num) {
                1 -> "东风"; 2 -> "南风"; 3 -> "西风"; 4 -> "北风"
                5 -> "红中"; 6 -> "发财"; else -> "白板"
            }
        }

    private val numCn: String
        get() = when (num) {
            1 -> "一"; 2 -> "二"; 3 -> "三"; 4 -> "四"; 5 -> "五"
            6 -> "六"; 7 -> "七"; 8 -> "八"; else -> "九"
        }

    companion object {
        /** 红中在字牌里的 num（癞子麻将用） */
        const val ZHONG_NUM = 5
        const val ZI_ID_BASE = 108

        /** 生成整副 136 张（withZi=false 时为 108 张，四川用） */
        fun fullDeck(withZi: Boolean): List<MjTile> {
            val tiles = ArrayList<MjTile>(if (withZi) 136 else 108)
            for (s in listOf(MjSuit.WAN, MjSuit.TONG, MjSuit.TIAO)) {
                for (num in 1..9) repeat(4) { c -> tiles += MjTile(idOf(s, num, c), s, num) }
            }
            if (withZi) {
                for (num in 1..7) repeat(4) { c -> tiles += MjTile(ZI_ID_BASE + (num - 1) * 4 + c, MjSuit.ZI, num) }
            }
            return tiles
        }

        fun idOf(suit: MjSuit, num: Int, copy: Int): Int = when (suit) {
            MjSuit.WAN -> (num - 1) * 4 + copy
            MjSuit.TONG -> 36 + (num - 1) * 4 + copy
            MjSuit.TIAO -> 72 + (num - 1) * 4 + copy
            MjSuit.ZI -> ZI_ID_BASE + (num - 1) * 4 + copy
        }

        private val ALL_136 = fullDeck(true)

        /** 由 id 还原牌（网络传输用） */
        fun byId(id: Int): MjTile? = ALL_136.getOrNull(id)

        fun byIds(ids: List<Int>): List<MjTile> = ids.mapNotNull { byId(it) }

        /** 语义化排序：万→筒→条→字，同门内 1..9 */
        val sortKey: (MjTile) -> Int = { it.code }
    }
}
