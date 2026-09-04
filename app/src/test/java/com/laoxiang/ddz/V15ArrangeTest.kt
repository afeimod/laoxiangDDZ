package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * v15 测试：掼蛋智能理牌（GdArrange）分区覆盖与标签、
 * 掼蛋双副牌混发排序（同点同花色相邻）、升级定主阶段快照与发牌顺序。
 */
class V15ArrangeTest {

    private fun infos4() = (0 until 4).map {
        PlayerInfo(it, "P$it", 1, it != 0, AiLevel.MEDIUM)
    }

    private fun c(r: Int, s: CardSuit, id: Int = r * 10 + s.ordinal) = Card(id, r, s)

    // ================================================= 掼蛋智能理牌

    @Test
    fun `掼蛋理牌分区完整覆盖且标签正确`() {
        val lr = 2
        val hand = listOf(
            // 同花顺 ♠34567（♠ 副无其余，不会延伸）
            c(3, CardSuit.SPADE), c(4, CardSuit.SPADE), c(5, CardSuit.SPADE), c(6, CardSuit.SPADE), c(7, CardSuit.SPADE),
            // 炸弹 9×4
            c(9, CardSuit.HEART), c(9, CardSuit.SPADE), c(9, CardSuit.CLUB), c(9, CardSuit.DIAMOND),
            // 木板 JJ QQ KK（♥♣ 各只三张，不构成顺）
            c(11, CardSuit.HEART), c(11, CardSuit.CLUB),
            c(12, CardSuit.HEART), c(12, CardSuit.CLUB),
            c(13, CardSuit.HEART), c(13, CardSuit.CLUB),
            // 对 A + 单 4(红) + 大小王各一
            c(14, CardSuit.HEART), c(14, CardSuit.DIAMOND),
            c(4, CardSuit.HEART),
            c(16, CardSuit.JOKER), c(17, CardSuit.JOKER)
        )
        val combos = GdArrange.arrange(hand, lr)
        // 完整覆盖：每张牌恰好出现一次
        val outIds = combos.flatMap { it.cards }.map { it.id }.sorted()
        assertEquals(hand.size, outIds.size)
        assertEquals(hand.map { it.id }.sorted(), outIds)
        // 标签
        val labels = combos.map { it.label }
        assertTrue("应识别同花顺", labels.contains("同花顺"))
        assertTrue("应识别炸弹", labels.contains("炸弹"))
        assertTrue("应识别木板", labels.contains("木板"))
        // 优先序：炸弹在最前（展示序高于同花顺）
        val firstMajor = combos.first { it.major }
        assertEquals("炸弹", firstMajor.label)
        // 王不成炸：作为普通牌保留
        val jokerCombo = combos.firstOrNull { it.cards.any { x -> x.suit == CardSuit.JOKER } }
        assertNotNull(jokerCombo)
    }

    @Test
    fun `掼蛋理牌天王炸与钢板`() {
        val lr = 3
        val hand = listOf(
            c(16, CardSuit.JOKER), c(16, CardSuit.JOKER), c(17, CardSuit.JOKER), c(17, CardSuit.JOKER),
            // 钢板 KKK AAA
            c(13, CardSuit.SPADE), c(13, CardSuit.HEART), c(13, CardSuit.CLUB),
            c(14, CardSuit.SPADE), c(14, CardSuit.HEART), c(14, CardSuit.CLUB),
            c(5, CardSuit.CLUB)
        )
        val combos = GdArrange.arrange(hand, lr)
        assertEquals(hand.size, combos.sumOf { it.cards.size })
        assertTrue("应识别天王炸", combos.any { it.label == "天王炸" })
        assertTrue("应识别钢板", combos.any { it.label == "钢板" })
    }

    @Test
    fun `掼蛋理牌顺子与同花顺优先`() {
        val lr = 2
        // 同点可组顺子也可组炸：5 张顺 + 额外一张凑炸的取舍由贪心决定，但必须全覆盖、不重复
        val hand = listOf(
            c(5, CardSuit.HEART), c(5, CardSuit.SPADE), c(5, CardSuit.CLUB), c(5, CardSuit.DIAMOND),
            c(6, CardSuit.HEART), c(6, CardSuit.SPADE),
            c(7, CardSuit.HEART), c(7, CardSuit.SPADE),
            c(8, CardSuit.HEART), c(8, CardSuit.SPADE),
            c(9, CardSuit.HEART)
        )
        val combos = GdArrange.arrange(hand, lr)
        val ids = combos.flatMap { it.cards }.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(hand.map { it.id }.toSet(), ids.toSet())
    }

    // ================================================= 掼蛋双副混发排序

    @Test
    fun `掼蛋手牌同点同花相邻不分先后`() {
        val e = GuandanEngine(21)
        e.newMatch(infos4())
        val hand = e.myHand(0)
        assertEquals(27, hand.size)
        // 每个牌力段内：同花色必须连续（A♠A♠A♥A♥…，不允许 ♠♥♦♣♠♥♦♣ 的第一副/第二副交错）
        var i = 0
        while (i < hand.size) {
            var j = i
            while (j < hand.size && GdRules.power(hand[j].rank, e.levelRank) == GdRules.power(hand[i].rank, e.levelRank)) j++
            val seg = hand.subList(i, j).withIndex().toList()
            seg.groupBy { it.value.suit }.values.forEach { cs ->
                val idxs = cs.map { it.index }.sorted()
                assertEquals(
                    "双副本应相邻",
                    (idxs.first() until idxs.first() + cs.size).toList(), idxs
                )
            }
            i = j
        }
    }

    @Test
    fun `掼蛋整副模拟仍通过`() {
        repeat(2) { seed ->
            val e = GuandanEngine(seed.toLong() * 77 + 3)
            e.newMatch(infos4())
            var guard = 0
            while (e.phase == Phase.PLAYING && guard++ < 4000) {
                val seat = e.currentTurn
                val ctx = GuandanAi.Ctx(
                    e.myHand(seat), e.lastMove, e.lastMoveSeat,
                    e.players.map { it.info.seat to it.hand.size }.toMap(),
                    e.players.filter { it.hand.isEmpty() }.map { it.info.seat }
                )
                val ai = GuandanAi(seat, AiLevel.MEDIUM, e.levelRank)
                val mv = ai.chooseMove(ctx)
                var ok = mv != null && e.play(seat, mv)
                if (!ok && e.lastMove != null) ok = e.pass(seat)
                assertTrue("seed=$seed 卡局", ok)
            }
            assertEquals(Phase.GAME_OVER, e.phase)
        }
    }

    // ================================================= 升级定主快照

    @Test
    fun `升级定主阶段快照含发牌顺序`() {
        val e = ShengjiEngine(33)
        e.newMatch(infos4())
        val snap = e.snapshotFor(0)
        assertEquals(Phase.BIDDING, snap.phase)
        assertNull(snap.trumpSuit)
        assertEquals(25, snap.seats[0].handDealOrder.size)
        assertEquals(0, snap.seats[1].handDealOrder.size)
        // 发牌顺序 = 真实发牌序列（引擎内部手牌序）
        assertEquals(e.players[0].hand.map { it.id }, snap.seats[0].handDealOrder.map { it.id })
        e.finishBidding()
        assertEquals(Phase.BURYING, e.phase)
        e.buryCards(e.dealer, e.autoBuryChoice())
        val snap2 = e.snapshotFor(0)
        assertEquals(Phase.PLAYING, snap2.phase)
        assertNotNull(snap2.trumpSuit)
        assertEquals(0, snap2.seats[0].handDealOrder.size)
        // 出牌顺序：庄家先出
        assertEquals(snap2.dealer, snap2.turn)
    }
}
