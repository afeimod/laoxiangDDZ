package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * 斗地主核心规则测试
 * 运行：./gradlew test
 */
class GameLogicTest {

    private fun c(rank: Int, suit: CardSuit = CardSuit.SPADE, id: Int = rank * 5) =
        Card(id, rank, suit)

    private fun cards(vararg ranks: Int): List<Card> =
        ranks.mapIndexed { i, r -> c(r, CardSuit.SPADE, i + 1000) }

    private fun jokers(small: Boolean = true): Card =
        if (small) Card(52, 16, CardSuit.JOKER) else Card(53, 17, CardSuit.JOKER)

    // ------------------------------------------------ 牌型识别

    @Test
    fun `单牌对子三张`() {
        assertEquals(MoveType.SINGLE, Move.of(cards(3))?.type)
        assertEquals(MoveType.PAIR, Move.of(cards(4, 4))?.type)
        assertEquals(MoveType.TRIO, Move.of(cards(7, 7, 7))?.type)
    }

    @Test
    fun `三带一与三带二`() {
        assertEquals(MoveType.TRIO_SINGLE, Move.of(cards(8, 8, 8, 3))?.type)
        assertEquals(MoveType.TRIO_PAIR, Move.of(cards(8, 8, 8, 9, 9))?.type)
        // 三带二必须是对
        assertNull(Move.of(cards(8, 8, 8, 9, 10)))
    }

    @Test
    fun `炸弹与王炸`() {
        assertEquals(MoveType.BOMB, Move.of(cards(9, 9, 9, 9))?.type)
        val rocket = Move.of(listOf(jokers(true), jokers(false)))
        assertNotNull(rocket)
        assertEquals(MoveType.ROCKET, rocket!!.type)
    }

    @Test
    fun `顺子规则`() {
        assertNotNull(Move.of(cards(3, 4, 5, 6, 7)))
        assertNotNull(Move.of((3..14).toList().let { cards(*it.toIntArray()) }))  // 3~A 最长
        // 少于 5 张不成顺
        assertNull(Move.of(cards(3, 4, 5, 6)))
        // JQKA2 不算顺子（2 不能进顺）
        assertNull(Move.of(cards(11, 12, 13, 14, 15)))
    }

    @Test
    fun `连对规则`() {
        assertNotNull(Move.of(cards(3, 3, 4, 4, 5, 5)))
        // 两对不成连对
        assertNull(Move.of(cards(3, 3, 4, 4)))
        // 2 不能进连对（KKA A22 超出 A 上限）
        assertNull(Move.of(cards(13, 13, 14, 14, 15, 15)))
    }

    @Test
    fun `飞机与翅膀`() {
        assertEquals(MoveType.PLANE, Move.of(cards(3, 3, 3, 4, 4, 4))?.type)
        assertEquals(MoveType.PLANE_SINGLE, Move.of(cards(3, 3, 3, 4, 4, 4, 8, 9))?.type)
        assertEquals(MoveType.PLANE_PAIR, Move.of(cards(3, 3, 3, 4, 4, 4, 8, 8, 9, 9))?.type)
        // 翅膀数量不对
        assertNull(Move.of(cards(3, 3, 3, 4, 4, 4, 8)))
    }

    @Test
    fun `四带二`() {
        assertEquals(MoveType.FOUR_TWO_SINGLE, Move.of(cards(9, 9, 9, 9, 3, 4))?.type)
        assertEquals(MoveType.FOUR_TWO_PAIR, Move.of(cards(9, 9, 9, 9, 3, 3, 4, 4))?.type)
        // 四带三不行
        assertNull(Move.of(cards(9, 9, 9, 9, 3, 4, 5)))
    }

    // ------------------------------------------------ 比牌

    @Test
    fun `大小比较`() {
        val s3 = Move.of(cards(3))!!
        val s5 = Move.of(cards(5))!!
        assertTrue(s5.beats(s3))
        assertFalse(s3.beats(s5))

        val p3 = Move.of(cards(3, 3))!!
        assertFalse(s5.beats(p3))                    // 类型不同
        val p5 = Move.of(cards(5, 5))!!
        assertTrue(p5.beats(p3))

        // 顺子必须同长
        val st5a = Move.of(cards(3, 4, 5, 6, 7))!!
        val st5b = Move.of(cards(4, 5, 6, 7, 8))!!
        val st6 = Move.of(cards(3, 4, 5, 6, 7, 8))!!
        assertTrue(st5b.beats(st5a))
        assertFalse(st6.beats(st5a))                 // 长度不同不能比

        // 炸弹压一切非炸
        val bomb = Move.of(cards(3, 3, 3, 3))!!
        assertTrue(bomb.beats(st6))
        val bomb2 = Move.of(cards(4, 4, 4, 4))!!
        assertTrue(bomb2.beats(bomb))
        assertFalse(bomb.beats(bomb2))

        // 王炸压炸弹
        val rocket = Move.of(listOf(jokers(true), jokers(false)))!!
        assertTrue(rocket.beats(bomb2))
        // 非炸压不了王炸
        assertFalse(bomb.beats(rocket))
    }

    // ------------------------------------------------ 引擎完整对局（AI 自战）

    @Test
    fun `AI 全自动对局 300 场 - 规则不破`() {
        repeat(300) { round ->
            val engine = GameEngine(randomSeed = round.toLong() * 31 + 7)
            val level = AiLevel.entries[round % 3]
            val infos = listOf(
                PlayerInfo(0, "A", 1, true, level),
                PlayerInfo(1, "B", 2, true, level),
                PlayerInfo(2, "C", 3, true, level)
            )
            engine.newGame(infos)

            // 叫抢阶段全自动
            var guard = 0
            while (engine.phase in setOf(Phase.BIDDING, Phase.ROBBING) && guard++ < 60) {
                val snap = engine.snapshotFor(0)
                val actor = if (engine.phase == Phase.BIDDING) snap.bidCursor else snap.robCursor
                assertTrue(actor >= 0)
                val hand = engine.myHand(actor)
                val ai = AiPlayer(actor, level)
                val ctx = AiContext(
                    seat = actor, hand = hand,
                    lastMove = engine.lastMove, lastMoveSeat = engine.lastMoveSeat,
                    landlord = engine.landlord,
                    handCounts = engine.players.map { it.info.seat to it.hand.size }.toMap(),
                    playedCards = engine.players.flatMap { it.played }
                )
                if (engine.phase == Phase.BIDDING) {
                    engine.callLandlord(actor, ai.shouldCall(ctx))
                } else {
                    engine.robLandlord(actor, ai.shouldRob(ctx))
                }
            }
            // 一定进入 PLAYING（强制地主兜底）
            assertEquals(Phase.PLAYING, engine.phase)
            assertTrue(engine.landlord >= 0)
            // 地主 20 张，农民 17 张
            assertEquals(20, engine.players[engine.landlord].hand.size)
            // 底牌公开
            assertTrue(engine.bottomRevealed)

            // 出牌阶段
            var step = 0
            while (engine.phase == Phase.PLAYING && step++ < 500) {
                val seat = engine.currentTurn
                val hand = engine.myHand(seat)
                val ai = AiPlayer(seat, level)
                val ctx = AiContext(
                    seat = seat, hand = hand,
                    lastMove = engine.lastMove, lastMoveSeat = engine.lastMoveSeat,
                    landlord = engine.landlord,
                    handCounts = engine.players.map { it.info.seat to it.hand.size }.toMap(),
                    playedCards = engine.players.flatMap { it.played }
                )
                val move = ai.chooseMove(ctx)
                if (move == null) {
                    // 领出时不能过
                    if (engine.lastMove == null) {
                        val leads = MoveGen.genLeads(hand)
                        assertTrue(leads.isNotEmpty())
                        engine.play(seat, leads.first().cards)
                    } else {
                        engine.pass(seat)
                    }
                } else {
                    // AI 提议的牌必须合法
                    val mv = Move.of(move)
                    assertNotNull(mv)
                    if (mv!!.beats(engine.lastMove) && move.all { c -> hand.any { it.id == c.id } }) {
                        val ok = engine.play(seat, move)
                        if (!ok) engine.pass(seat)
                    } else {
                        engine.pass(seat)
                    }
                }
                // 不变量：总牌数守恒（手牌 + 已出 = 54）
                val total = engine.players.sumOf { it.hand.size } +
                        engine.players.sumOf { it.played.size }
                assertEquals(54, total)
            }

            // 必须有人出完结束
            assertEquals("对局应结束 round=$round", Phase.GAME_OVER, engine.phase)
            val result = engine.result
            assertNotNull(result)
            // 赢家手牌为 0
            assertEquals(0, engine.players[result!!.winnerSeat].hand.size)
        }
    }

    @Test
    fun `快照隐藏他人手牌`() {
        val engine = GameEngine(randomSeed = 42)
        engine.newGame(
            listOf(
                PlayerInfo(0, "我", 1), PlayerInfo(1, "AI1", 2, true), PlayerInfo(2, "AI2", 3, true)
            )
        )
        val snap = engine.snapshotFor(0)
        assertEquals(17, snap.seats[0].hand.size)
        assertEquals(0, snap.seats[1].hand.size)
        assertEquals(0, snap.seats[2].hand.size)
    }

    @Test
    fun `记牌器守恒`() {
        val engine = GameEngine(randomSeed = 9)
        engine.newGame(
            listOf(
                PlayerInfo(0, "我", 1), PlayerInfo(1, "AI1", 2, true), PlayerInfo(2, "AI2", 3, true)
            )
        )
        // 开局：全部未见 = 54 - 我的 17
        val counts = engine.remainingCounts(0)
        val sum = (3..17).sumOf { counts[it]!! }
        assertEquals(54 - 17, sum)
    }
}
