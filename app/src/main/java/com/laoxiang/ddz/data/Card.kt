package com.laoxiang.ddz.data

/**
 * 斗地主扑克牌数据模型（纯 Kotlin，无 Android 依赖，可单元测试）
 *
 * 牌值 rank 约定：
 * 3=3, 4=4, ..., 10=10, J=11, Q=12, K=13, A=14, 2=15, 小王=16, 大王=17
 * 大小即斗地主牌力顺序：3 < 4 < ... < K < A < 2 < 小王 < 大王
 */
@kotlinx.serialization.Serializable
enum class CardSuit(val label: String) {
    SPADE("♠"),   // 黑桃
    HEART("♥"),   // 红桃
    DIAMOND("♦"), // 方块
    CLUB("♣"),    // 梅花
    JOKER("王");  // 大小王

    val isRed: Boolean get() = this == HEART || this == DIAMOND || this == JOKER
}

@kotlinx.serialization.Serializable
data class Card(
    /** 全局唯一 id（0..53），用于网络传输与选中状态 */
    val id: Int,
    /** 牌力 3..17 */
    val rank: Int,
    val suit: CardSuit
) : Comparable<Card> {
    override fun compareTo(other: Card): Int =
        compareValuesBy(this, other, { it.rank }, { it.suit })

    /** 王者：小王 0 / 大王 1，非王 null */
    val jokerIndex: Int? get() = if (suit == CardSuit.JOKER) rank - 16 else null

    /** 展示字符：2、3..10、J Q K A、小王/大王 */
    val displayLabel: String
        get() = when (rank) {
            2 -> "2"
            in 3..10 -> rank.toString()
            11 -> "J"
            12 -> "Q"
            13 -> "K"
            14 -> "A"
            15 -> "2"
            16 -> "小王"
            17 -> "大王"
            else -> "?"
        }
}

object Deck {
    const val TOTAL = 54

    /** 生成有序整副牌（id 顺序即牌序） */
    fun fullDeck(): List<Card> {
        val cards = ArrayList<Card>(TOTAL)
        var id = 0
        val suits = listOf(CardSuit.SPADE, CardSuit.HEART, CardSuit.DIAMOND, CardSuit.CLUB)
        for (rank in 3..15) {
            for (suit in suits) {
                cards += Card(id++, rank, suit)
            }
        }
        cards += Card(id++, 16, CardSuit.JOKER) // 小王
        cards += Card(id++, 17, CardSuit.JOKER) // 大王
        return cards
    }

    /**
     * 双副牌 108 张（掼蛋/升级用）：第一副 id 0..53，第二副 id 54..107。
     * 牌面 2..A（rank 2..14）+ 双王，掼蛋 2 最小、升级 2 为普通点数。
     */
    fun doubleDeck(): List<Card> {
        val cards = ArrayList<Card>(108)
        val suits = listOf(CardSuit.SPADE, CardSuit.HEART, CardSuit.DIAMOND, CardSuit.CLUB)
        var id = 0
        repeat(2) {
            for (rank in 2..14) {
                for (suit in suits) {
                    cards += Card(id++, rank, suit)
                }
            }
            cards += Card(id++, 16, CardSuit.JOKER) // 小王
            cards += Card(id++, 17, CardSuit.JOKER) // 大王
        }
        return cards
    }

    /** 由 id 列表还原牌 */
    fun byIds(ids: List<Int>): List<Card> {
        val deck = fullDeck()
        return ids.mapNotNull { id -> deck.getOrNull(id) }
    }

    /** 对应 drawable 资源名，如 card_3s / card_10h / card_qs / card_joker_big */
    fun resName(card: Card): String = when (card.suit) {
        CardSuit.JOKER -> if (card.rank == 17) "card_joker_big" else "card_joker_small"
        else -> {
            val r = when (card.rank) {
                11 -> "j"; 12 -> "q"; 13 -> "k"; 14 -> "a"; 15 -> "2"; else -> card.rank.toString()
            }
            "card_${r}${card.suit.name.first().lowercase()}"
        }
    }
}
