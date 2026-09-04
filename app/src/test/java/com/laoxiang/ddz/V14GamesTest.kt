package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * v14 新游戏引擎测试：
 * 1. 锄大地：牌库 52 张/每人 13、方块3先出且首手含方块3、整局模拟无卡局、先出完者胜、
 *    牌型识别（顺子 23456 / 10JQKA、葫芦、铁支、同花顺、同点比花色）
 * 2. 掼蛋：牌库 108 张/每人 27、级牌与炸弹大小、三连对/钢板/同花顺识别、
 *    整副模拟无卡局、二游出完即结算、升级数值、终局判断
 * 3. 升级：发牌 25+底8、主牌序列、拖拉机识别、整副模拟无卡局、得分与升级、终局判断
 */
class V14GamesTest {

    private fun infos4() = (0 until 4).map {
        PlayerInfo(it, "P$it", 1, it != 0, AiLevel.MEDIUM)
    }

    /** 定主 + 庄家自动扣底（v17 后出牌前必经阶段） */
    private fun ShengjiEngine.finishAndBury() {
        finishBidding()
        assertEquals(Phase.BURYING, phase)
        assertTrue(buryCards(dealer, autoBuryChoice()))
        assertEquals(Phase.PLAYING, phase)
    }

    // ================================================= 锄大地

    @Test
    fun `锄大地发牌与方块3先手`() {
        val e = BigTwoEngine(42)
        e.newGame(infos4())
        assertEquals(52, e.players.sumOf { it.hand.size })
        assertEquals(13, e.players[0].hand.size)
        val d3Seat = e.players.indexOfFirst { it.hand.any { c -> c.rank == 3 && c.suit == CardSuit.DIAMOND } }
        assertEquals("持方块3者先出", d3Seat, e.currentTurn)
        // 首手必须含方块3：不含方块3的出牌应被拒绝
        val hand = e.myHand(d3Seat)
        val noD3 = hand.filter { !(it.rank == 3 && it.suit == CardSuit.DIAMOND) }
        if (noD3.size >= 1 && BtMove.of(listOf(noD3.first())) != null) {
            assertFalse(e.play(d3Seat, listOf(noD3.first())))
        }
    }

    @Test
    fun `锄大地牌型识别`() {
        val c = { r: Int, s: CardSuit -> Card(r * 4 + s.ordinal, r, s) }
        // 23456 顺子（2 作小牌，端点 6）
        val low = BtMove.of(listOf(c(15, CardSuit.CLUB), c(3, CardSuit.CLUB), c(4, CardSuit.HEART), c(5, CardSuit.SPADE), c(6, CardSuit.CLUB)))
        assertEquals(BtType.STRAIGHT, low?.type)
        // 同花 23456 = 同花顺
        val lowSF = BtMove.of(listOf(c(15, CardSuit.CLUB), c(3, CardSuit.CLUB), c(4, CardSuit.CLUB), c(5, CardSuit.CLUB), c(6, CardSuit.CLUB)))
        assertEquals(BtType.STRAIGHT_FLUSH, lowSF?.type)
        assertEquals(6, low?.mainRank)
        // 10JQKA
        val high = BtMove.of(listOf(c(10, CardSuit.SPADE), c(11, CardSuit.SPADE), c(12, CardSuit.SPADE), c(13, CardSuit.SPADE), c(14, CardSuit.SPADE)))
        assertEquals(BtType.STRAIGHT_FLUSH, high?.type)
        assertEquals(14, high?.mainRank)
        // JQKA2 混色不构成顺子（非同花非对型 → null）
        assertNull(BtMove.of(listOf(c(11, CardSuit.CLUB), c(12, CardSuit.HEART), c(13, CardSuit.CLUB), c(14, CardSuit.SPADE), c(15, CardSuit.CLUB))))
        // JQKA2 同花 = 合法同花（非同花顺）
        assertEquals(BtType.FLUSH, BtMove.of(listOf(c(11, CardSuit.CLUB), c(12, CardSuit.CLUB), c(13, CardSuit.CLUB), c(14, CardSuit.CLUB), c(15, CardSuit.CLUB)))?.type)
        // 葫芦 / 铁支
        val fh = BtMove.of(listOf(c(5, CardSuit.CLUB), c(5, CardSuit.SPADE), c(5, CardSuit.HEART), c(9, CardSuit.CLUB), c(9, CardSuit.SPADE)))
        assertEquals(BtType.FULLHOUSE, fh?.type)
        assertEquals(5, fh?.mainRank)
        val quad = BtMove.of(listOf(c(7, CardSuit.CLUB), c(7, CardSuit.SPADE), c(7, CardSuit.HEART), c(7, CardSuit.DIAMOND), c(3, CardSuit.CLUB)))
        assertEquals(BtType.QUAD, quad?.type)
        // 同点单张比花色：♠2 > ♥2
        val s2 = BtMove.of(listOf(c(15, CardSuit.SPADE)))!!
        val h2 = BtMove.of(listOf(c(15, CardSuit.HEART)))!!
        assertTrue(s2.beats(h2))
        assertFalse(h2.beats(s2))
    }

    @Test
    fun `锄大地整局模拟`() {
        repeat(5) { seed ->
            val e = BigTwoEngine(seed.toLong() * 131)
            e.newGame(infos4())
            var guard = 0
            while (e.phase == Phase.PLAYING && guard++ < 3000) {
                val seat = e.currentTurn
                val ai = BigTwoAi(seat, AiLevel.MEDIUM)
                val ctx = BigTwoAi.Ctx(
                    e.myHand(seat), e.lastMove, e.lastMoveSeat,
                    e.players.map { it.info.seat to it.hand.size }.toMap(),
                    e.players.flatMap { it.played }
                )
                val mv = ai.chooseMove(ctx)
                val ok = mv != null && e.play(seat, mv)
                if (!ok) {
                    val passed = if (e.hasTable()) e.pass(seat) else false
                    if (!passed) {
                        // 最终兜底：出最小单张
                        val h = e.myHand(seat)
                        assertTrue("seed=$seed 卡局", h.isEmpty() || e.play(seat, listOf(h.last())))
                    }
                }
            }
            assertEquals("seed=$seed 应正常结束", Phase.GAME_OVER, e.phase)
            assertNotNull(e.result)
            assertEquals("赢家剩 0 张", 0, e.result!!.remain[e.result!!.winnerSeat]!!)
            assertEquals("总分守恒", 0, e.result!!.scoreDelta.values.sum())
        }
    }

    // ================================================= 掼蛋

    @Test
    fun `掼蛋发牌与牌型`() {
        val e = GuandanEngine(7)
        e.newMatch(infos4())
        assertEquals(108, e.players.sumOf { it.hand.size })
        assertEquals(27, e.players[0].hand.size)
        assertEquals(2, e.levelRank)

        val lr = e.levelRank
        val c = { r: Int, s: CardSuit -> Card(r * 4 + s.ordinal, r, s) }
        // 三连对（木板）
        val banzi = GdMove.of(
            listOf(c(3, CardSuit.CLUB), c(3, CardSuit.SPADE), c(4, CardSuit.CLUB), c(4, CardSuit.SPADE), c(5, CardSuit.CLUB), c(5, CardSuit.SPADE)), lr
        )
        assertEquals(GdType.BANZI, banzi?.type)
        // 二连三（钢板）
        val gangban = GdMove.of(
            listOf(c(6, CardSuit.CLUB), c(6, CardSuit.SPADE), c(6, CardSuit.HEART), c(7, CardSuit.CLUB), c(7, CardSuit.SPADE), c(7, CardSuit.HEART)), lr
        )
        assertEquals(GdType.GANGBAN, gangban?.type)
        // A2345 顺子（端点5）
        val aLow = GdMove.of(
            listOf(c(14, CardSuit.CLUB), c(2, CardSuit.HEART), c(3, CardSuit.CLUB), c(4, CardSuit.SPADE), c(5, CardSuit.CLUB)), lr
        )
        assertEquals(GdType.STRAIGHT, aLow?.type)
        assertEquals(5, aLow?.mainPower)
        // 同花顺压 5 张以内炸弹
        val sf = GdMove.of(
            listOf(c(5, CardSuit.SPADE), c(6, CardSuit.SPADE), c(7, CardSuit.SPADE), c(8, CardSuit.SPADE), c(9, CardSuit.SPADE)), lr
        )!!
        assertEquals(GdType.STRAIGHT_FLUSH, sf.type)
        val bomb4 = GdMove.of(listOf(c(3, CardSuit.CLUB), c(3, CardSuit.SPADE), c(3, CardSuit.HEART), c(3, CardSuit.DIAMOND)), lr)!!
        assertTrue(sf.beats(bomb4, lr))
        // 6 张炸弹压同花顺
        val bomb6 = GdMove.of(listOf(
            c(4, CardSuit.CLUB), c(4, CardSuit.SPADE), c(4, CardSuit.HEART), c(4, CardSuit.DIAMOND),
            c(4, CardSuit.CLUB), c(4, CardSuit.SPADE)), lr
        )!!
        assertEquals(GdType.BOMB, bomb6.type)
        assertTrue(bomb6.beats(sf, lr))
        // 天王炸
        val rocket = GdMove.of(listOf(
            Card(52, 16, CardSuit.JOKER), Card(53, 17, CardSuit.JOKER),
            Card(106, 16, CardSuit.JOKER), Card(107, 17, CardSuit.JOKER)), lr)!!
        assertEquals(GdType.ROCKET, rocket.type)
        assertTrue(rocket.beats(bomb6, lr))
        // 级牌单张 > A
        val levelCard = GdMove.of(listOf(c(lr, CardSuit.CLUB)), lr)!!
        val ace = GdMove.of(listOf(c(14, CardSuit.SPADE)), lr)!!
        assertTrue(levelCard.beats(ace, lr))
    }

    @Test
    fun `掼蛋整副模拟`() {
        repeat(4) { seed ->
            val e = GuandanEngine(seed.toLong() * 77 + 3)
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
                if (!ok && e.lastMove != null) {
                    val passed = e.pass(seat)
                    if (!passed) {
                        val h = e.myHand(seat)
                        assertTrue("seed=$seed 卡局", h.isEmpty() || e.play(seat, listOf(h.first())))
                    }
                } else if (!ok) {
                    val h = e.myHand(seat)
                    assertTrue("seed=$seed 领出卡局", h.isEmpty() || e.play(seat, listOf(h.first())))
                }
            }
            assertEquals("seed=$seed 应进入结算", Phase.GAME_OVER, e.phase)
            val r = e.result
            assertNotNull(r)
            assertEquals("二游出完即结算，出牌+剩牌=108",
                108, e.players.sumOf { it.played.size + it.hand.size })
            assertTrue(r!!.upgrade in 1..3)
            assertTrue(r.newLevels.getValue(r.winnerTeam) in 2..14)
        }
    }

    // ================================================= 升级

    @Test
    fun `升级发牌与主牌序列`() {
        val e = ShengjiEngine(11)
        e.newMatch(infos4())
        // 定主阶段：108 张一起发（不分第一副第二副），25×4 + 底 8，亮主者坐庄
        assertEquals(Phase.BIDDING, e.phase)
        assertEquals(100, e.players.sumOf { it.hand.size })
        assertEquals(25, e.players[0].hand.size)
        assertEquals(8, e.kitty.size)
        assertTrue(e.levelRank in 2..14)
        e.finishBidding()
        // v17：庄家捡底后进入扣底阶段（手牌 33），扣回 8 张后开局
        assertEquals(Phase.BURYING, e.phase)
        assertEquals(33, e.players[e.dealer].hand.size)
        assertEquals(25, e.players[0].hand.size)
        assertEquals(8, e.kitty.size)
        assertTrue(e.buryCards(e.dealer, e.autoBuryChoice()))
        assertEquals(Phase.PLAYING, e.phase)
        assertEquals(25, e.players[0].hand.size)
        assertEquals(8, e.kitty.size)
    }

    @Test
    fun `升级定主竞标优先级`() {
        repeat(60) { seed ->
            val e = ShengjiEngine(seed.toLong() * 91 + 7)
            e.newMatch(infos4())
            assertTrue(e.phase == Phase.BIDDING)
            // 找持对级牌者（tier 2）与单张级牌者（tier 1）
            val pairSeat = (0..3).firstOrNull { s ->
                CardSuit.values().any { e.claimTierOf(s, it) >= 2 }
            }
            val singleSeat = (0..3).firstOrNull { s ->
                s != pairSeat && CardSuit.values().any { e.claimTierOf(s, it) == 1 }
            }
            if (pairSeat != null && singleSeat != null) {
                val pairSuit = CardSuit.values().first { e.claimTierOf(pairSeat, it) >= 2 }
                val singleSuit = CardSuit.values().first { e.claimTierOf(singleSeat, it) == 1 }
                // 单张先亮 → 对级牌反主 → 更低/同级被拒
                assertTrue(e.claimTrump(singleSeat, singleSuit))
                assertEquals(1, e.claimTier)
                assertTrue(e.claimTrump(pairSeat, pairSuit))
                assertEquals(2, e.claimTier)
                assertFalse(e.claimTrump(singleSeat, singleSuit))
                // 定主：亮主者坐庄、主花色=反主花色；扣底后开局
                e.finishBidding()
                assertEquals(Phase.BURYING, e.phase)
                assertTrue(e.buryCards(e.dealer, e.autoBuryChoice()))
                assertEquals(Phase.PLAYING, e.phase)
                assertEquals(pairSeat, e.dealer)
                assertEquals(pairSuit, e.trumpSuit)
                assertEquals(25, e.players[0].hand.size)
                return
            }
        }
        // 60 个种子必有对级牌+单张级牌共存（概率上必然），走到这里视为失败
        assert(false)
    }

    @Test
    fun `升级无主亮主与自动定主`() {
        // 对大王 → 亮无主（tier 4，抢主顺序最高）
        repeat(200) { seed ->
            val e = ShengjiEngine(seed.toLong() * 13 + 1)
            e.newMatch(infos4())
            val ntSeat = (0..3).firstOrNull { s -> e.claimTierOf(s, null) >= 3 }
            if (ntSeat != null) {
                val tier = e.claimTierOf(ntSeat, null)
                assertTrue(e.claimTrump(ntSeat, null))
                assertEquals(tier, e.claimTier)
                assertTrue(e.claimNT)
                e.finishBidding()
                e.buryCards(e.dealer, e.autoBuryChoice())
                assertEquals(CardSuit.JOKER, e.trumpSuit)   // 无主哨兵
                assertEquals(ntSeat, e.dealer)
                // 无主时：主牌=双王+级牌；副花色照常跟牌（引擎不崩溃即过）
                assertEquals(25, e.players[0].hand.size)
                return
            }
        }
        // 找不到对王也属正常（概率约 1/5/种子），验证自动定主路径
        val e = ShengjiEngine(999)
        e.newMatch(infos4())
        e.finishBidding()
        e.buryCards(e.dealer, e.autoBuryChoice())
        assertEquals(Phase.PLAYING, e.phase)
    }

    @Test
    fun `升级花色分组排序与双副相邻`() {
        val e = ShengjiEngine(5)
        e.newMatch(infos4())
        e.finishAndBury()
        val hand = e.myHand(0)
        val t = e.trumpSuit; val lr = e.levelRank
        // 用 id 映射位置（双副牌同点同花卡牌 equals 相同，不能用 indexOf）
        val entries = hand.withIndex().filter { !SjRules.isTrump(it.value, t, lr) }
        entries.groupBy { it.value.suit }.values.forEach { cards ->
            val idxs = cards.map { it.index }.sorted()
            assertEquals("同花色应连续成块", (idxs.first()..idxs.last()).toList(), idxs)
            // 同点同花色两副本相邻（不分第一副第二副）
            cards.groupBy { it.value.rank }.forEach { (_, cs) ->
                val cidx = cs.map { it.index }.sorted()
                assertEquals("双副本应相邻", (cidx.first() until cidx.first() + cs.size).toList(), cidx)
            }
        }
    }

    @Test
    fun `升级拖拉机识别`() {
        val t = CardSuit.HEART
        val lr = 10
        val c = { r: Int, s: CardSuit -> Card(r * 4 + s.ordinal, r, s) }
        // 副牌拖拉机：♣55 66
        val p1 = parsePlay(
            listOf(c(5, CardSuit.CLUB), c(5, CardSuit.CLUB), c(6, CardSuit.CLUB), c(6, CardSuit.CLUB)), t, lr
        )
        assertEquals(SjType.TRACTOR, p1?.type)
        // 非相邻对不是拖拉机 → 组合
        val p2 = parsePlay(
            listOf(c(5, CardSuit.CLUB), c(5, CardSuit.CLUB), c(8, CardSuit.CLUB), c(8, CardSuit.CLUB)), t, lr
        )
        assertEquals(SjType.THROW, p2?.type)
        // 主牌拖拉机：♥AA + 小王对（A 主牌内序 14，小王 17 —— 不相邻；v19 起混合甩牌不能领出 → null）
        val p3 = parsePlay(
            listOf(c(14, t), c(14, t), Card(60, 16, CardSuit.JOKER), Card(114, 16, CardSuit.JOKER)), t, lr
        )
        assertEquals(null, p3)
        // 同一手牌作为跟牌垫牌（allowMixed）仍是合法组合
        val p3b = parsePlay(
            listOf(c(14, t), c(14, t), Card(60, 16, CardSuit.JOKER), Card(114, 16, CardSuit.JOKER)),
            t, lr, allowMixed = true
        )
        assertEquals(SjType.THROW, p3b?.type)
        // 副10对 + 主10对 相邻 → 拖拉机
        val p4 = parsePlay(
            listOf(c(10, CardSuit.CLUB), c(10, CardSuit.CLUB), c(10, t), c(10, t)), t, lr
        )
        assertEquals(SjType.TRACTOR, p4?.type)
    }

    @Test
    fun `升级整副模拟`() {
        repeat(4) { seed ->
            val e = ShengjiEngine(seed.toLong() * 91 + 5)
            e.newMatch(infos4())
            e.finishAndBury()   // V2：定主竞标 + 庄家扣底完成后才开局
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
                    // 兜底（v17 跟牌规则）：领出副牌=该花色非主牌（级牌属主不算）；领出主牌=全部主牌
                    val hand = e.myHand(seat)
                    val led = e.ledPlay()
                    val n = led?.count ?: 1
                    val inSuit = if (led == null) emptyList()
                    else if (led.suit == CardSuit.JOKER) hand.filter { SjRules.isTrump(it, e.trumpSuit, e.levelRank) }
                    else hand.filter { it.suit == led.suit && !SjRules.isTrump(it, e.trumpSuit, e.levelRank) }
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
            val r = e.result
            assertNotNull(r)
            assertTrue("闲家得分范围", r!!.oppPoints >= 0)
        }
    }
}
