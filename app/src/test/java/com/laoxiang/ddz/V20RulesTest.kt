package com.laoxiang.ddz

import com.laoxiang.ddz.data.AiLevel
import com.laoxiang.ddz.data.Card
import com.laoxiang.ddz.data.CardSuit
import com.laoxiang.ddz.data.GdMove
import com.laoxiang.ddz.data.GdRules
import com.laoxiang.ddz.data.GuandanEngine
import com.laoxiang.ddz.data.Phase
import com.laoxiang.ddz.data.PlayerInfo
import com.laoxiang.ddz.data.SjRules
import com.laoxiang.ddz.data.SjType
import com.laoxiang.ddz.data.ShengjiAi
import com.laoxiang.ddz.data.ShengjiEngine
import com.laoxiang.ddz.data.followStructureOk
import com.laoxiang.ddz.data.parsePlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v20 规则修复回归：
 * 1. 升级结构化跟牌 —— 领出对子有对必须跟对（可拆三/四张成对）；领出拖拉机有同长拖拉机必须跟，
 *    无拖拉机必须跟出全部对子；无该花色可垫/主杀（对标升级竞赛规则）
 * 2. AI 跟牌建议与引擎校验同一口径（followSuggestion 生成 → validatePlay 必过）
 * 3. 掼蛋结算 —— 双下立即结束升 3 级；非双下须打出三游：三游与头游同队+2、异队+1
 * 4. 掼蛋判圈 —— 剩 2/3 人时一圈正常结束（passStreak 按在场人数），不卡死
 */
class V20RulesTest {

    private fun infos4() = (0 until 4).map {
        PlayerInfo(it, "P$it", 1, true, AiLevel.MEDIUM)
    }

    private var idSeq = 0

    private fun c(rank: Int, suit: CardSuit): Card =
        Card(rank * 1000 + suit.ordinal * 100 + (idSeq++), rank, suit)

    // ------------------------------------------------ 升级：结构化跟牌校验

    @Test
    fun `领出对子有对必须跟对`() {
        val t = CardSuit.CLUB; val lr = 2
        val led = parsePlay(listOf(c(5, CardSuit.SPADE), c(5, CardSuit.SPADE)), t, lr)
        assertNotNull(led)
        assertEquals(SjType.PAIR, led!!.type)
        // 手牌：KK♠（对）+ 9♠ 3♠（散）+ 红桃
        val hand = listOf(
            c(13, CardSuit.SPADE), c(13, CardSuit.SPADE),
            c(9, CardSuit.SPADE), c(3, CardSuit.SPADE),
            c(4, CardSuit.HEART), c(6, CardSuit.HEART)
        )
        // 有对方跟两张散单 → 拒绝
        assertFalse(
            followStructureOk(led, hand, listOf(c(9, CardSuit.SPADE), c(3, CardSuit.SPADE)), t, lr)
        )
        // 跟对 → 通过
        assertTrue(
            followStructureOk(led, hand, listOf(c(13, CardSuit.SPADE), c(13, CardSuit.SPADE)), t, lr)
        )
        // 三张 K 拆对：KKK♠ 出 KK → 通过
        val hand3 = listOf(
            c(13, CardSuit.SPADE), c(13, CardSuit.SPADE), c(13, CardSuit.SPADE),
            c(9, CardSuit.SPADE), c(3, CardSuit.SPADE)
        )
        assertTrue(
            followStructureOk(led, hand3, listOf(c(13, CardSuit.SPADE), c(13, CardSuit.SPADE)), t, lr)
        )
        // 无对方（全散单）→ 跟两张散单通过
        val handNoPair = listOf(c(9, CardSuit.SPADE), c(3, CardSuit.SPADE), c(4, CardSuit.HEART))
        assertTrue(
            followStructureOk(
                led, handNoPair, listOf(c(9, CardSuit.SPADE), c(3, CardSuit.SPADE)), t, lr
            )
        )
    }

    @Test
    fun `领出对子无该花色可垫可杀`() {
        val t = CardSuit.CLUB; val lr = 2
        val led = parsePlay(listOf(c(5, CardSuit.SPADE), c(5, CardSuit.SPADE)), t, lr)!!
        // 无该花色：任意两张垫牌（含主杀）→ 只约束张数
        val hand = listOf(c(4, CardSuit.HEART), c(6, CardSuit.HEART), c(2, CardSuit.CLUB))
        assertTrue(followStructureOk(led, hand, listOf(c(4, CardSuit.HEART), c(6, CardSuit.HEART)), t, lr))
        assertTrue(followStructureOk(led, hand, listOf(c(2, CardSuit.CLUB), c(4, CardSuit.HEART)), t, lr))
        // 花色张不足（仅 1 张♠）→ 跟 1 张 + 1 垫
        val handShort = listOf(c(9, CardSuit.SPADE), c(4, CardSuit.HEART), c(6, CardSuit.HEART))
        assertTrue(
            followStructureOk(led, handShort, listOf(c(9, CardSuit.SPADE), c(4, CardSuit.HEART)), t, lr)
        )
    }

    @Test
    fun `领出拖拉机有同长拖拉机必须跟拖拉机`() {
        val t = CardSuit.CLUB; val lr = 2
        // 领出 3344♠ 拖拉机
        val ledCards = listOf(
            c(3, CardSuit.SPADE), c(3, CardSuit.SPADE),
            c(4, CardSuit.SPADE), c(4, CardSuit.SPADE)
        )
        val led = parsePlay(ledCards, t, lr)
        assertNotNull(led)
        assertEquals(SjType.TRACTOR, led!!.type)
        // 手牌：8899♠（拖拉机）+ KK♠ 55♠（散对）+ 散单
        val hand = listOf(
            c(8, CardSuit.SPADE), c(8, CardSuit.SPADE),
            c(9, CardSuit.SPADE), c(9, CardSuit.SPADE),
            c(13, CardSuit.SPADE), c(13, CardSuit.SPADE),
            c(5, CardSuit.SPADE), c(5, CardSuit.SPADE)
        )
        // 有拖拉机跟两个不相邻散对（KK+55）→ 拒绝
        assertFalse(
            followStructureOk(
                led, hand,
                listOf(c(13, CardSuit.SPADE), c(13, CardSuit.SPADE), c(5, CardSuit.SPADE), c(5, CardSuit.SPADE)),
                t, lr
            )
        )
        // 跟同长拖拉机 8899 → 通过
        assertTrue(
            followStructureOk(
                led, hand,
                listOf(c(8, CardSuit.SPADE), c(8, CardSuit.SPADE), c(9, CardSuit.SPADE), c(9, CardSuit.SPADE)),
                t, lr
            )
        )
    }

    @Test
    fun `领出拖拉机无拖拉机须跟出全部对子`() {
        val t = CardSuit.CLUB; val lr = 2
        val led = parsePlay(
            listOf(
                c(3, CardSuit.SPADE), c(3, CardSuit.SPADE),
                c(4, CardSuit.SPADE), c(4, CardSuit.SPADE)
            ), t, lr
        )!!
        // 手牌：KK♠ 55♠（两对，不相邻）+ 散单 9♠ 3♠
        val hand = listOf(
            c(13, CardSuit.SPADE), c(13, CardSuit.SPADE),
            c(5, CardSuit.SPADE), c(5, CardSuit.SPADE),
            c(9, CardSuit.SPADE), c(3, CardSuit.SPADE)
        )
        // 跟一对 + 两散 → 拒绝（须跟出全部 2 对）
        assertFalse(
            followStructureOk(
                led, hand,
                listOf(c(13, CardSuit.SPADE), c(13, CardSuit.SPADE), c(9, CardSuit.SPADE), c(3, CardSuit.SPADE)),
                t, lr
            )
        )
        // 跟两对（KK+55，无需相邻）→ 通过
        assertTrue(
            followStructureOk(
                led, hand,
                listOf(c(13, CardSuit.SPADE), c(13, CardSuit.SPADE), c(5, CardSuit.SPADE), c(5, CardSuit.SPADE)),
                t, lr
            )
        )
        // 只有 1 对：KK♠ + 散单 → 跟 KK + 2 散 → 通过
        val handOnePair = listOf(
            c(13, CardSuit.SPADE), c(13, CardSuit.SPADE),
            c(9, CardSuit.SPADE), c(3, CardSuit.SPADE), c(7, CardSuit.SPADE)
        )
        assertTrue(
            followStructureOk(
                led, handOnePair,
                listOf(c(13, CardSuit.SPADE), c(13, CardSuit.SPADE), c(9, CardSuit.SPADE), c(3, CardSuit.SPADE)),
                t, lr
            )
        )
        // 只有 1 对却跟 4 张散 → 拒绝
        assertFalse(
            followStructureOk(
                led, handOnePair,
                listOf(c(9, CardSuit.SPADE), c(3, CardSuit.SPADE), c(7, CardSuit.SPADE), c(13, CardSuit.SPADE)),
                t, lr
            )
        )
    }

    @Test
    fun `领出主牌对子有主对必须跟主对`() {
        val t = CardSuit.CLUB; val lr = 2
        // 领出主牌对子（♣5，♣为主花色 → 主牌）
        val led = parsePlay(listOf(c(5, CardSuit.CLUB), c(5, CardSuit.CLUB)), t, lr)!!
        assertEquals(SjType.PAIR, led.type)
        // 手牌：主牌对（♣K）+ 散主（♣9、小王）+ 副牌
        val hand = listOf(
            c(13, CardSuit.CLUB), c(13, CardSuit.CLUB),
            c(9, CardSuit.CLUB),
            Card(900, 16, CardSuit.JOKER),
            c(4, CardSuit.HEART), c(6, CardSuit.HEART)
        )
        // 跟主对 → 通过
        assertTrue(
            followStructureOk(led, hand, listOf(c(13, CardSuit.CLUB), c(13, CardSuit.CLUB)), t, lr)
        )
        // 有主对却跟两张散主（♣9 + 小王，不构成对）→ 拒绝
        assertFalse(
            followStructureOk(
                led, hand,
                listOf(c(9, CardSuit.CLUB), Card(900, 16, CardSuit.JOKER)), t, lr
            )
        )
        // 跟一对 ♣9？只有一张 → 无法；跟散 ♣9 + 副牌 → 张数内跟足 1 张主（池 4>2 须跟 2 主）→ 拒绝
        assertFalse(
            followStructureOk(
                led, hand,
                listOf(c(9, CardSuit.CLUB), c(4, CardSuit.HEART)), t, lr
            )
        )
    }

    // ------------------------------------------------ 升级：AI 建议 = 引擎口径

    @Test
    fun `AI跟牌建议必过引擎校验`() {
        val t = CardSuit.CLUB; val lr = 2
        // 场景1：领出对子，池内有对
        val ledPair = parsePlay(listOf(c(5, CardSuit.SPADE), c(5, CardSuit.SPADE)), t, lr)!!
        val pool1 = listOf(
            c(9, CardSuit.SPADE), c(9, CardSuit.SPADE), c(3, CardSuit.SPADE), c(7, CardSuit.SPADE)
        )
        val s1 = ShengjiAi.followSuggestion(pool1, ledPair, t, lr)
        assertEquals(2, s1.size)
        assertTrue(followStructureOk(ledPair, pool1, s1, t, lr))
        // 场景2：领出拖拉机，无拖拉机有 2 对
        val ledTr = parsePlay(
            listOf(
                c(3, CardSuit.SPADE), c(3, CardSuit.SPADE),
                c(4, CardSuit.SPADE), c(4, CardSuit.SPADE)
            ), t, lr
        )!!
        val pool2 = listOf(
            c(13, CardSuit.SPADE), c(13, CardSuit.SPADE),
            c(5, CardSuit.SPADE), c(5, CardSuit.SPADE),
            c(9, CardSuit.SPADE), c(3, CardSuit.SPADE)
        )
        val s2 = ShengjiAi.followSuggestion(pool2, ledTr, t, lr)
        assertEquals(4, s2.size)
        assertTrue(followStructureOk(ledTr, pool2, s2, t, lr))
        // 场景3：领出拖拉机，有同长拖拉机
        val pool3 = listOf(
            c(8, CardSuit.SPADE), c(8, CardSuit.SPADE),
            c(9, CardSuit.SPADE), c(9, CardSuit.SPADE),
            c(13, CardSuit.SPADE), c(13, CardSuit.SPADE)
        )
        val s3 = ShengjiAi.followSuggestion(pool3, ledTr, t, lr)
        assertEquals(4, s3.size)
        assertTrue(followStructureOk(ledTr, pool3, s3, t, lr))
        // 场景4：甩牌含对（unit==2）
        val ledThrow = parsePlay(
            listOf(c(10, CardSuit.HEART), c(10, CardSuit.HEART), c(6, CardSuit.HEART)),
            t, lr
        )!!
        assertEquals(SjType.THROW, ledThrow.type)
        assertEquals(2, ledThrow.unit)
        val pool4 = listOf(
            c(13, CardSuit.HEART), c(13, CardSuit.HEART), c(9, CardSuit.HEART), c(3, CardSuit.HEART)
        )
        val s4 = ShengjiAi.followSuggestion(pool4, ledThrow, t, lr)
        assertEquals(3, s4.size)
        assertTrue(followStructureOk(ledThrow, pool4, s4, t, lr))
    }

    @Test
    fun `升级AI全自动对局两副均合法完成`() {
        val e = ShengjiEngine(7)
        e.newMatch(infos4())
        var hands = 0
        while (hands < 2) {
            autoPlaySj(e)
            assertEquals(Phase.GAME_OVER, e.phase)
            hands++
            if (!e.nextHandIfPossible()) break
        }
        assertTrue(hands >= 1)
    }

    /** 升级全自动对局驱动：定主 → 扣底 → 依结构化建议出牌，断言每手合法 */
    private fun autoPlaySj(e: ShengjiEngine, maxSteps: Int = 4000) {
        var steps = 0
        while (e.phase != Phase.GAME_OVER && steps++ < maxSteps) {
            when (e.phase) {
                Phase.BIDDING -> e.finishBidding()
                Phase.BURYING -> e.buryCards(e.dealer, e.autoBuryChoice())
                Phase.PLAYING -> {
                    val seat = e.currentTurn
                    val hand = e.myHand(seat)
                    val led = e.ledPlay()
                    val move: List<Card> = if (led == null) {
                        listOf(hand.first())
                    } else {
                        val pool = if (led.suit == CardSuit.JOKER)
                            hand.filter { SjRules.isTrump(it, e.trumpSuit, e.levelRank) }
                        else hand.filter {
                            it.suit == led.suit && !SjRules.isTrump(it, e.trumpSuit, e.levelRank)
                        }
                        if (pool.size >= led.count) {
                            ShengjiAi.followSuggestion(pool, led, e.trumpSuit, e.levelRank)
                        } else {
                            val rest = hand.filter { c -> pool.none { it.id == c.id } }
                            (pool + rest).take(led.count)
                        }
                    }
                    assertTrue("AI 出牌被拒 seat=$seat move=$move", e.play(seat, move))
                }
                else -> return
            }
        }
        assertTrue("对局超步数未结束", e.phase == Phase.GAME_OVER)
    }

    // ------------------------------------------------ 掼蛋：三游结算 + 判圈

    private fun gdEngineWithHands(
        h0: List<Card>, h1: List<Card>, h2: List<Card>, h3: List<Card>
    ): GuandanEngine {
        val e = GuandanEngine(randomSeed = 11)
        e.newMatch(infos4())
        e.players.forEachIndexed { i, p ->
            p.hand.clear()
            p.hand += when (i) {
                0 -> h0; 1 -> h1; 2 -> h2; else -> h3
            }
        }
        return e
    }

    private fun autoPlayGd(e: GuandanEngine, maxSteps: Int = 300) {
        var steps = 0
        while (e.phase == Phase.PLAYING && steps++ < maxSteps) {
            val snap = e.snapshotFor(0)
            val turn = snap.turn
            val last = snap.lastMove
            val hand = e.players[turn].hand
            if (last == null) {
                assertTrue(
                    e.play(turn, listOf(hand.minBy { GdRules.power(it.rank, e.levelRank) }))
                )
            } else {
                val canBeat = hand.filter {
                    GdMove.of(listOf(it), e.levelRank)?.beats(last, e.levelRank) == true
                }
                if (canBeat.isNotEmpty()) {
                    assertTrue(
                        e.play(turn, listOf(canBeat.minBy { GdRules.power(it.rank, e.levelRank) }))
                    )
                } else {
                    assertTrue(e.pass(turn))
                }
            }
        }
        assertEquals(Phase.GAME_OVER, e.phase)
    }

    @Test
    fun `掼蛋双下立即结算升3级`() {
        // 0(A)大王、2(A)QK 先后出完 → 双下；1(B)34、3(B)56 压不过
        val e = gdEngineWithHands(
            h0 = listOf(Card(1000, 17, CardSuit.JOKER)),
            h1 = listOf(c(3, CardSuit.SPADE), c(4, CardSuit.SPADE)),
            h2 = listOf(c(12, CardSuit.HEART), c(13, CardSuit.HEART)),
            h3 = listOf(c(5, CardSuit.DIAMOND), c(6, CardSuit.DIAMOND))
        )
        autoPlayGd(e)
        val r = e.result!!
        assertEquals(0, r.headSeat)
        assertEquals(2, r.secondSeat)
        assertEquals(3, r.upgrade)
    }

    @Test
    fun `掼蛋非双下打出三游同队升2级`() {
        // 0(A)大王头游；3(B)KQ 二游；2(A)567 三游（与头游同队）→ +2（旧代码二游一出现即错误给 +1）
        val e = gdEngineWithHands(
            h0 = listOf(Card(1000, 17, CardSuit.JOKER)),
            h1 = listOf(c(3, CardSuit.SPADE), c(4, CardSuit.SPADE)),
            h2 = listOf(c(5, CardSuit.HEART), c(6, CardSuit.HEART), c(7, CardSuit.HEART)),
            h3 = listOf(c(13, CardSuit.DIAMOND), c(12, CardSuit.DIAMOND))
        )
        autoPlayGd(e)
        val r = e.result!!
        assertEquals(0, r.headSeat)
        assertEquals(3, r.secondSeat)
        assertEquals(2, r.upgrade)
    }

    @Test
    fun `掼蛋非双下三游异队升1级`() {
        // 0(A)大王头游；3(B)AK 二游；1(B)QJ 三游（与头游异队）→ +1
        val e = gdEngineWithHands(
            h0 = listOf(Card(1000, 17, CardSuit.JOKER)),
            h1 = listOf(c(12, CardSuit.SPADE), c(11, CardSuit.SPADE)),
            h2 = listOf(c(5, CardSuit.HEART), c(8, CardSuit.HEART), c(9, CardSuit.HEART)),
            h3 = listOf(c(14, CardSuit.DIAMOND), c(13, CardSuit.DIAMOND))
        )
        autoPlayGd(e)
        val r = e.result!!
        assertEquals(0, r.headSeat)
        assertEquals(3, r.secondSeat)
        assertEquals(1, r.upgrade)
    }
}
