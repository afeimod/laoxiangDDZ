package com.laoxiang.ddz

import com.laoxiang.ddz.data.AiLevel
import com.laoxiang.ddz.data.Card
import com.laoxiang.ddz.data.CardSuit
import com.laoxiang.ddz.data.Phase
import com.laoxiang.ddz.data.PlayerInfo
import com.laoxiang.ddz.data.SjRules
import com.laoxiang.ddz.data.SjType
import com.laoxiang.ddz.data.ShengjiAi
import com.laoxiang.ddz.data.ShengjiEngine
import com.laoxiang.ddz.data.biggestGroup
import com.laoxiang.ddz.data.parsePlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v19 规则修复回归：
 * 1. 「四个2」假拖拉机根修 —— 级牌按「点数+花色」成组，不同花色级牌不能成对/混甩领出
 * 2. 主牌邻接特例（副级对/主级对/小王对连档）保留
 * 3. 领出甩牌必须同花色，王与级牌不参与甩牌
 * 4. 一圈结算后整圈牌面保留（lastTrick），第四家出牌可见，下一圈领出清空
 */
class V19RulesTest {

    private fun infos4() = (0 until 4).map {
        PlayerInfo(it, "P$it", 1, true, AiLevel.MEDIUM)
    }

    private fun c(rank: Int, suit: CardSuit, id: Int = rank * 4 + suit.ordinal) =
        Card(id, rank, suit)

    // ------------------------------------------------ 四个2 / 级牌成组

    @Test
    fun `不同花色级牌不能成对也不能领出`() {
        val t = CardSuit.CLUB
        val lr = 2
        // 2♠ + 2♦：不同花色级牌不是对子，也不能领出（v19：领出非对非同花 → 非法）
        val two = parsePlay(listOf(c(2, CardSuit.SPADE), c(2, CardSuit.DIAMOND)), t, lr)
        assertNull("2♠2♦ 不能领出", two)
        // 同样两张牌跟牌垫牌合法
        val twoF = parsePlay(
            listOf(c(2, CardSuit.SPADE), c(2, CardSuit.DIAMOND)), t, lr, allowMixed = true
        )
        assertEquals(SjType.THROW, twoF?.type)
        // 四个 2（各一张）作为领出：非法
        val four = parsePlay(
            listOf(c(2, CardSuit.SPADE), c(2, CardSuit.DIAMOND), c(2, CardSuit.HEART), c(2, CardSuit.CLUB)),
            t, lr
        )
        assertNull("四个2 不能领出", four)
        // 四个 2（含一对 2♣）作为领出：仍非法（混甩）
        val four2 = parsePlay(
            listOf(c(2, CardSuit.SPADE, 100), c(2, CardSuit.DIAMOND, 101), c(2, CardSuit.CLUB, 102), c(2, CardSuit.CLUB, 103)),
            t, lr
        )
        assertNull("2♠2♦+2♣2♣ 不能领出", four2)
        // 同一手牌跟牌垫牌合法（allowMixed），内含最大组只有 2♣2♣ 一个对（unit=2，非拖拉机）
        val follow = parsePlay(
            listOf(c(2, CardSuit.SPADE, 100), c(2, CardSuit.DIAMOND, 101), c(2, CardSuit.CLUB, 102), c(2, CardSuit.CLUB, 103)),
            t, lr, allowMixed = true
        )
        assertEquals(SjType.THROW, follow?.type)
        assertEquals(2, follow?.unit)
    }

    @Test
    fun `主牌邻接特例与真拖拉机`() {
        val t = CardSuit.CLUB
        val lr = 2
        // 小王对 + 主级牌对（2♣2♣）→ 拖拉机
        val p1 = parsePlay(
            listOf(Card(60, 16, CardSuit.JOKER), Card(114, 16, CardSuit.JOKER), c(2, t, 1), c(2, t, 2)), t, lr
        )
        assertEquals(SjType.TRACTOR, p1?.type)
        // 小王对 + 副级牌对（2♠2♠）→ 隔了主级档（15→17 差 2）→ 不是拖拉机
        val p2 = parsePlay(
            listOf(Card(60, 16, CardSuit.JOKER), Card(114, 16, CardSuit.JOKER), c(2, CardSuit.SPADE, 3), c(2, CardSuit.SPADE, 4)), t, lr
        )
        assertNull(p2)
        // 主A对（♣A♣A）+ 副级对（2♠2♠）→ 主牌头部连档（14↔15）→ 拖拉机
        val p2b = parsePlay(
            listOf(c(14, t, 7), c(14, t, 8), c(2, CardSuit.SPADE, 3), c(2, CardSuit.SPADE, 4)), t, lr
        )
        assertEquals(SjType.TRACTOR, p2b?.type)
        // 副级对 + 主级对 → 拖拉机（15-16 连档）
        val p3 = parsePlay(
            listOf(c(2, CardSuit.SPADE, 3), c(2, CardSuit.SPADE, 4), c(2, t, 1), c(2, t, 2)), t, lr
        )
        assertEquals(SjType.TRACTOR, p3?.type)
        // 副级对 + 副级对（2♠2♠2♦2♦）→ 不是拖拉机（同级不同花色不连）
        val p4 = parsePlay(
            listOf(c(2, CardSuit.SPADE, 3), c(2, CardSuit.SPADE, 4), c(2, CardSuit.DIAMOND, 5), c(2, CardSuit.DIAMOND, 6)), t, lr
        )
        assertNull(p4)
        // 普通副牌拖拉机不受影响：♣44♣55
        val p5 = parsePlay(
            listOf(c(4, CardSuit.CLUB), c(4, CardSuit.CLUB), c(5, CardSuit.CLUB), c(5, CardSuit.CLUB)), t, lr
        )
        assertEquals(SjType.TRACTOR, p5?.type)
    }

    @Test
    fun `领出甩牌必须同花色且王级牌不参与甩牌`() {
        val t = CardSuit.CLUB
        val lr = 2
        // 同花色甩牌仍可领出：♣AA + ♣K
        val ok = parsePlay(
            listOf(c(14, CardSuit.CLUB), c(14, CardSuit.CLUB, 60), c(13, CardSuit.CLUB)), t, lr
        )
        assertEquals(SjType.THROW, ok?.type)
        // 主花色甩牌（含主级牌）可领出：♣AA + 2♣
        val ok2 = parsePlay(
            listOf(c(14, t), c(14, t, 61), c(2, t, 62)), t, lr
        )
        assertEquals(SjType.THROW, ok2?.type)
        // 王与主花色混甩 → 非法
        val bad1 = parsePlay(
            listOf(Card(70, 17, CardSuit.JOKER), c(13, t)), t, lr
        )
        assertNull("大王+♣K 不能混甩", bad1)
        // 级牌与副牌花色混甩 → 非法
        val bad2 = parsePlay(
            listOf(c(2, CardSuit.SPADE), c(13, CardSuit.SPADE)), t, lr
        )
        assertNull("2♠+♠K 不能混甩（2♠属主牌）", bad2)
    }

    @Test
    fun `biggestGroup 不再把不同花色级牌算成对`() {
        val t = CardSuit.CLUB
        val lr = 2
        // 2♠2♦（不同花色级牌）→ 无对，unit=1
        val g1 = biggestGroup(listOf(c(2, CardSuit.SPADE), c(2, CardSuit.DIAMOND)), t, lr)
        assertEquals(1, g1.first)
        // 2♣2♣（同花色主级对）→ unit=2
        val g2 = biggestGroup(listOf(c(2, t, 1), c(2, t, 2)), t, lr)
        assertEquals(2, g2.first)
    }

    @Test
    fun `AI 不会生成含假对子的拖拉机`() {
        val t = CardSuit.CLUB
        val lr = 2
        // 手牌含 2♠2♠2♦2♦2♣2♣+小王对：AI findTractor 只能返回真连档
        val hand = listOf(
            c(2, CardSuit.SPADE, 1), c(2, CardSuit.SPADE, 2),
            c(2, CardSuit.DIAMOND, 3), c(2, CardSuit.DIAMOND, 4),
            c(2, t, 5), c(2, t, 6),
            Card(60, 16, CardSuit.JOKER), Card(114, 16, CardSuit.JOKER)
        )
        val tract = ShengjiAi.findTractor(hand, com.laoxiang.ddz.data.CtxLike(t, lr), trump = true)
        assertNotNull(tract)
        val p = parsePlay(tract!!, t, lr)
        assertEquals("AI 给出的拖拉机必须合法", SjType.TRACTOR, p?.type)
        assertEquals(6, tract.size)
        // 只有一对（无相邻对）时绝不能返回"拖拉机"
        val singlePair = listOf(
            c(2, CardSuit.SPADE, 1), c(2, CardSuit.SPADE, 2),
            c(2, t, 5), Card(60, 16, CardSuit.JOKER), c(5, CardSuit.HEART, 9), c(9, CardSuit.HEART, 10)
        )
        assertNull("单对不能当拖拉机", ShengjiAi.findTractor(singlePair, com.laoxiang.ddz.data.CtxLike(t, lr), trump = true))
    }

    @Test
    fun `一圈结算后整圈保留展示且下一圈领出清空`() {
        val e = ShengjiEngine(19)
        e.newMatch(infos4())
        e.finishBidding()
        assertTrue(e.buryCards(e.dealer, e.autoBuryChoice()))
        assertEquals(Phase.PLAYING, e.phase)

        var guard = 0
        while (e.lastTrick.isEmpty() && guard++ < 200) {
            val seat = e.currentTurn
            val led = e.ledPlay()
            val hand = e.myHand(seat)
            val mv: List<Card> = if (led == null) {
                listOf(hand.first())
            } else {
                val inSuit = if (led.suit == CardSuit.JOKER)
                    hand.filter { SjRules.isTrump(it, e.trumpSuit, e.levelRank) }
                else hand.filter { it.suit == led.suit && !SjRules.isTrump(it, e.trumpSuit, e.levelRank) }
                if (inSuit.isNotEmpty()) listOf(inSuit.minBy { it.rank })
                else listOf(hand.filter { c -> c.rank != e.levelRank }.minBy { it.rank })
            }
            assertTrue("出牌应合法 seat=$seat", e.play(seat, mv))
        }
        assertTrue(guard < 200)
        // 一圈结束：整圈保留、进行中清空、快照可见
        assertEquals(4, e.lastTrick.size)
        assertEquals(0, e.trickPlays.size)
        assertTrue(e.lastTrickWinner in 0..3)
        assertEquals(4, e.snapshotFor(0).lastTrick.size)
        assertEquals(e.lastTrickWinner, e.snapshotFor(0).lastTrickWinner)

        // 下一圈领出 → 保留展示清空
        val leader = e.currentTurn
        assertTrue(e.play(leader, listOf(e.myHand(leader).first())))
        assertEquals(0, e.lastTrick.size)
        assertEquals(-1, e.lastTrickWinner)
        assertEquals(1, e.trickPlays.size)
    }

    @Test
    fun `新规则下整局模拟不卡局`() {
        repeat(6) { seed ->
            val e = ShengjiEngine(seed.toLong() * 197 + 3)
            e.newMatch(infos4())
            e.finishBidding()
            assertTrue(e.buryCards(e.dealer, e.autoBuryChoice()))
            var guard = 0
            while (e.phase == Phase.PLAYING && guard++ < 5000) {
                val seat = e.currentTurn
                val ai = ShengjiAi(seat, AiLevel.MEDIUM)
                val ctx = ShengjiAi.Ctx(
                    e.myHand(seat), e.trumpSuit, e.levelRank, e.dealer,
                    e.ledPlay(), e.trickPlays.toList(), e.oppPoints
                )
                val mv = ai.chooseMove(ctx)
                var ok = mv != null && e.play(seat, mv)
                if (!ok) {
                    val hand = e.myHand(seat)
                    val led = e.ledPlay()
                    val n = led?.count ?: 1
                    val inSuit = if (led != null && led.suit != CardSuit.JOKER)
                        hand.filter { it.suit == led.suit && !SjRules.isTrump(it, e.trumpSuit, e.levelRank) }
                    else if (led != null) hand.filter { SjRules.isTrump(it, e.trumpSuit, e.levelRank) }
                    else emptyList()
                    val pick = if (led != null && inSuit.size >= n) inSuit.sortedBy { it.rank }.take(n)
                    else if (led != null) {
                        val rest = hand.filter { c -> inSuit.none { it.id == c.id } }.sortedBy { it.rank }
                        (inSuit + rest).take(n)
                    } else listOf(hand.first())
                    ok = e.play(seat, pick)
                }
                assertTrue("seed=$seed 卡局 seat=$seat", ok)
            }
            assertEquals("seed=$seed 应进入结算", Phase.GAME_OVER, e.phase)
            assertNotNull(e.result)
        }
    }
}
