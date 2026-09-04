package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * v17 规则修复测试：
 * 1. 掼蛋：等级升级提交（修「一直打2」）、逢人配（红桃级牌百搭，不能配王）
 * 2. 升级：扣底阶段（捡底 8 → 手动扣 8 → 开局）、跟牌/主杀
 *   （无该花色可主杀；级牌属主不算该花色；有该花色必须跟；主牌领出有主须跟主）、等级升级提交
 */
class V17RulesTest {

    private fun infos4() = (0 until 4).map {
        PlayerInfo(it, "P$it", 1, it != 0, AiLevel.MEDIUM)
    }

    private fun ShengjiEngine.finishAndBury() {
        finishBidding()
        assertEquals(Phase.BURYING, phase)
        assertTrue(buryCards(dealer, autoBuryChoice()))
        assertEquals(Phase.PLAYING, phase)
    }

    // ================================================= 掼蛋

    @Test
    fun `掼蛋等级升级提交不再一直打2`() {
        val e = GuandanEngine(66)
        e.newMatch(infos4())
        var guard = 0
        while (e.phase == Phase.PLAYING && guard++ < 5000) {
            val seat = e.currentTurn
            val ai = GuandanAi(seat, AiLevel.MEDIUM, e.levelRank)
            val ctx = GuandanAi.Ctx(
                e.myHand(seat), e.lastMove, e.lastMoveSeat,
                e.players.map { it.info.seat to it.hand.size }.toMap(),
                e.players.filter { it.hand.isEmpty() }.map { it.info.seat }
            )
            val mv = ai.chooseMove(ctx)
            val ok = mv != null && e.play(seat, mv)
            if (!ok && e.lastMove != null) e.pass(seat)
        }
        assertEquals(Phase.GAME_OVER, e.phase)
        val r = e.result ?: return fail("应有结算")
        // 等级必须真正提交（此前 newLevels 只进 result，teamLevels 永远停在 2）
        assertEquals(r.newLevels, e.teamLevels.toMap())
        assertEquals(2 + r.upgrade, e.teamLevels.getValue(r.winnerTeam))
        // 下一副打赢家队的新等级（≥3，不再永远打 2）
        assertTrue(e.nextHandIfPossible())
        assertEquals(e.teamLevels.getValue(gdTeamOf(r.headSeat)), e.levelRank)
        assertTrue("下一副级牌应 ≥3", e.levelRank >= 3)
    }

    @Test
    fun `掼蛋逢人配百搭`() {
        val lr = 10
        val c = { r: Int, s: CardSuit -> Card(r * 4 + s.ordinal, r, s) }
        val wild = c(lr, CardSuit.HEART)
        // 配同花顺：♥10 + ♠3456 → ♠34567（逢人配当黑桃7/2）
        val sf = GdMove.of(
            listOf(wild, c(3, CardSuit.SPADE), c(4, CardSuit.SPADE), c(5, CardSuit.SPADE), c(6, CardSuit.SPADE)), lr
        )
        assertEquals(GdType.STRAIGHT_FLUSH, sf?.type)
        assertEquals(7, sf?.mainPower)
        // 配高同花顺：♥10 + ♠JQKA → ♠10JQKA
        val sf2 = GdMove.of(
            listOf(wild, c(11, CardSuit.SPADE), c(12, CardSuit.SPADE), c(13, CardSuit.SPADE), c(14, CardSuit.SPADE)), lr
        )
        assertEquals(GdType.STRAIGHT_FLUSH, sf2?.type)
        assertEquals(14, sf2?.mainPower)
        // 配炸弹：♥10 + 三个 8 → 四张 8 炸
        val bomb = GdMove.of(
            listOf(wild, c(8, CardSuit.CLUB), c(8, CardSuit.DIAMOND), c(8, CardSuit.HEART)), lr
        )
        assertEquals(GdType.BOMB, bomb?.type)
        assertEquals(8, bomb?.mainPower)
        // 配三张：♥10 + 对 5 → 三张 5
        val trio = GdMove.of(listOf(wild, c(5, CardSuit.CLUB), c(5, CardSuit.DIAMOND)), lr)
        assertEquals(GdType.TRIO, trio?.type)
        assertEquals(5, trio?.mainPower)
        // 单张逢人配 = 级牌单张（大过 A）
        val single = GdMove.of(listOf(wild), lr)!!
        assertEquals(GdType.SINGLE, single.type)
        assertEquals(16, single.mainPower)
        assertTrue(single.beats(GdMove.of(listOf(c(14, CardSuit.SPADE)), lr)!!, lr))
        // 双逢人配 = 对级牌（power 16）
        val wild2 = c(lr, CardSuit.HEART).copy(id = 999)
        val jp = GdMove.of(listOf(wild, wild2), lr)!!
        assertEquals(GdType.PAIR, jp.type)
        assertEquals(16, jp.mainPower)
        // 不能代替王：♥10 + 小王 → 非法
        assertNull(GdMove.of(listOf(wild, Card(90, 16, CardSuit.JOKER)), lr))
        // 普通顺子中逢人配取最大端：♥10 + 3456 → 34567
        val st = GdMove.of(
            listOf(wild, c(3, CardSuit.CLUB), c(4, CardSuit.DIAMOND), c(5, CardSuit.HEART), c(6, CardSuit.SPADE)), lr
        )!!
        assertEquals(GdType.STRAIGHT, st.type)
        assertEquals(7, st.mainPower)
    }

    // ================================================= 升级：扣底阶段

    @Test
    fun `升级扣底阶段规则`() {
        val e = ShengjiEngine(45)
        e.newMatch(infos4())
        e.finishBidding()
        assertEquals(Phase.BURYING, e.phase)
        val dealer = e.dealer
        val hand = e.players[dealer].hand
        assertEquals("捡底后庄家 33 张", 33, hand.size)
        assertEquals(8, e.kitty.size)
        // 扣底阶段快照暴露底牌（供 UI 展示）
        assertEquals(8, e.snapshotFor(0).kitty.size)
        // 张数错误 / 非庄家扣底被拒
        assertFalse(e.buryCards(dealer, hand.take(7)))
        assertFalse(e.buryCards((dealer + 1) % 4, hand.take(8)))
        // 正确扣 8 张 → 开局、庄家领出、手牌 25、牌数守恒
        val bury = hand.take(8)
        assertTrue(e.buryCards(dealer, bury))
        assertEquals(Phase.PLAYING, e.phase)
        assertEquals(25, e.players[dealer].hand.size)
        assertEquals(bury.map { it.id }, e.kitty.map { it.id })
        assertEquals(dealer, e.currentTurn)
        assertEquals(108, e.players.sumOf { it.played.size + it.hand.size } + e.kitty.size)
        // 开局后快照不再暴露底牌
        assertEquals(0, e.snapshotFor(0).kitty.size)
    }

    // ================================================= 升级：跟牌 / 主杀

    @Test
    fun `升级跟牌主杀与级牌不算花色`() {
        val e = ShengjiEngine(21)
        e.newMatch(infos4())
        e.finishAndBury()
        val t = e.trumpSuit
        val lr = e.levelRank
        val side = CardSuit.values().first { it != CardSuit.JOKER && it != t }
        val other2 = CardSuit.values().first { it != CardSuit.JOKER && it != t && it != side }
        val c = { r: Int, s: CardSuit -> Card(r * 4 + s.ordinal, r, s) }
        val r1 = (5..8).first { it != lr }
        // 手工布置手牌（每人 2 张）
        val d = e.dealer
        e.players[d].hand.clear(); e.players[d].hand += listOf(c(r1, side), c(4, t))
        e.players[(d + 1) % 4].hand.clear(); e.players[(d + 1) % 4].hand += listOf(c(3, t), c(4, t))
        e.players[(d + 2) % 4].hand.clear(); e.players[(d + 2) % 4].hand += listOf(c(r1 + 1, side), c(13, t))
        e.players[(d + 3) % 4].hand.clear(); e.players[(d + 3) % 4].hand += listOf(c(lr, side), c(9, other2))

        // 庄领出副牌单张
        assertTrue(e.play(d, listOf(c(r1, side))))
        // 下家无该花色 → 可以用主杀
        assertTrue(e.play((d + 1) % 4, listOf(c(3, t))))
        // 下下家有该花色 → 不能用主牌杀，必须跟花色
        assertFalse("有该花色必须跟，不能主杀", e.play((d + 2) % 4, listOf(c(13, t))))
        assertTrue(e.play((d + 2) % 4, listOf(c(r1 + 1, side))))
        // 末家只有该花色级牌（属主牌，不算该花色）→ 可垫别的花色
        assertTrue("级牌不算领出花色，可自由垫/杀", e.play((d + 3) % 4, listOf(c(9, other2))))

        // 第二圈：赢家领出（各剩 1 张）
        val lead2 = e.currentTurn
        val leadCard = e.players[lead2].hand.first()
        assertTrue(e.play(lead2, listOf(leadCard)))
        // 主牌领出后：所有还有主的人必须跟主
        for (i in 1..3) {
            val seat = (lead2 + i) % 4
            val hd = e.players[seat].hand
            assertTrue(hd.isNotEmpty())
            val c0 = hd[0]
            if (SjRules.isTrump(c0, t, lr)) {
                assertTrue("有主须跟主 seat=$seat", e.play(seat, listOf(c0)))
            } else {
                // 手里无主 → 可任意垫
                assertTrue(e.play(seat, listOf(c0)))
            }
        }
        assertEquals(Phase.GAME_OVER, e.phase)
    }

    @Test
    fun `升级等级升级提交不再一直打2`() {
        val e = ShengjiEngine(31)
        e.newMatch(infos4())
        e.finishAndBury()
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
                } else listOf(hand.last())
                ok = e.play(seat, pick)
            }
            assertTrue("卡局 seat=$seat", ok)
        }
        assertEquals(Phase.GAME_OVER, e.phase)
        val r = e.result ?: return fail("应有结算")
        assertEquals(r.newLevels, e.teamLevels.toMap())
        if (r.upgrade > 0) {
            assertEquals(2 + r.upgrade, e.teamLevels.getValue(r.winnerTeam))
        }
        // 下一副打庄方队伍的当前等级（等级连续推进）
        assertTrue(e.nextHandIfPossible())
        assertEquals(e.teamLevels.getValue(gdTeamOf(e.dealer)), e.levelRank)
    }
}
