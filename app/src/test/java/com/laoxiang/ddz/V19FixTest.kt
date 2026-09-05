package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import com.laoxiang.ddz.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/**
 * v19 回归：
 * 1) 掼蛋 genGdBeats 逢人配感知（此前红桃级牌只能按自身点数参与，
 *    「3 同张+百搭=炸弹」「单张+百搭=对子」「百搭补顺」全被漏掉，
 *    AI 明明能压却 pass——用户观感"出过牌就立刻继续，不等下家压住"）
 * 2) 掼蛋整局模拟（wild-aware AI 不卡局、可正常终局）
 * 3) 联机网络层重构回归（OutboundQueue 异步写 + 真实 TCP 链路）
 */
class V19FixTest {

    private fun c(id: Int, rank: Int, suit: CardSuit) = Card(id, rank, suit)

    // ---------------- 逢人配压牌枚举 ----------------

    /** 3 同张+红桃级牌 = 炸弹，必须能枚举出来压别人的小炸弹 */
    @Test
    fun `逢人配补炸弹 三同张加百搭`() {
        val level = 10
        val hand = listOf(c(1, 13, CardSuit.SPADE), c(2, 13, CardSuit.HEART), c(3, 13, CardSuit.DIAMOND), c(4, 10, CardSuit.HEART))
        val bomb3 = GdMove.of(listOf(c(9, 3, CardSuit.SPADE), c(10, 3, CardSuit.HEART), c(11, 3, CardSuit.DIAMOND), c(12, 3, CardSuit.CLUB)), level)!!
        val beats = GuandanAi.genGdBeats(hand, bomb3, level, withBomb = true)
        assertTrue("应枚举出 KKK+百搭 炸弹", beats.any { it.size == 4 && it.count { x -> x.rank == 13 } == 3 })
    }

    /** 单张+红桃级牌 = 对子，能压别人的小对子（此前枚举不到 → AI 乱 pass） */
    @Test
    fun `逢人配补对子 单张加百搭`() {
        val level = 10
        val hand = listOf(c(1, 12, CardSuit.SPADE), c(2, 10, CardSuit.HEART), c(3, 3, CardSuit.CLUB))
        val pairJ = GdMove.of(listOf(c(9, 11, CardSuit.SPADE), c(10, 11, CardSuit.HEART)), level)!!
        val beats = GuandanAi.genGdBeats(hand, pairJ, level, withBomb = false)
        assertTrue("应枚举出 Q+百搭 对子", beats.any { it.size == 2 && it.any { x -> x.rank == 12 } })
        val pair4 = GdMove.of(listOf(c(9, 4, CardSuit.SPADE), c(10, 4, CardSuit.HEART)), level)!!
        val beats4 = GuandanAi.genGdBeats(hand, pair4, level, withBomb = false)
        assertTrue("小对子也该有 3+百搭 对子", beats4.isNotEmpty())
    }

    /** 百搭补顺：5 6 7 9 + 百搭(当8) = 5~9 顺子 */
    @Test
    fun `逢人配补顺子`() {
        val level = 10
        val hand = listOf(
            c(1, 5, CardSuit.SPADE), c(2, 6, CardSuit.HEART), c(3, 7, CardSuit.DIAMOND),
            c(4, 9, CardSuit.CLUB), c(5, 10, CardSuit.HEART)
        )
        val straight8 = GdMove.of(listOf(c(9, 4, CardSuit.SPADE), c(10, 5, CardSuit.HEART), c(11, 6, CardSuit.DIAMOND), c(12, 7, CardSuit.CLUB), c(13, 8, CardSuit.SPADE)), level)!!
        val beats = GuandanAi.genGdBeats(hand, straight8, level, withBomb = false)
        assertTrue("应枚举出百搭补 8 的 5~9 顺子", beats.isNotEmpty())
    }

    /** AI 跟牌：对手出对 J，手里 Q+百搭 能压 → chooseMove 不再返回 null */
    @Test
    fun `AI不再放弃逢人配可压的对子`() {
        val level = 10
        val hand = listOf(c(1, 12, CardSuit.SPADE), c(2, 10, CardSuit.HEART), c(3, 3, CardSuit.CLUB), c(4, 4, CardSuit.DIAMOND))
        val pairJ = GdMove.of(listOf(c(9, 11, CardSuit.SPADE), c(10, 11, CardSuit.HEART)), level)!!
        val ai = GuandanAi(0, AiLevel.MEDIUM, level)
        val move = ai.chooseMove(
            GuandanAi.Ctx(
                hand = hand, lastMove = pairJ, lastMoveSeat = 1,
                handCounts = mapOf(0 to 4, 1 to 20, 2 to 25, 3 to 25), finishedSeats = emptyList()
            )
        )
        assertNotNull("对手出的对子，手里有 Q+百搭 必须能跟", move)
        assertEquals(2, move!!.size)
    }

    /** 引擎链路：玩家打出 3 同张+百搭，引擎按炸弹受理 */
    @Test
    fun `引擎接受逢人配炸弹出牌`() {
        val level = 10
        val cards = listOf(c(1, 13, CardSuit.SPADE), c(2, 13, CardSuit.HEART), c(3, 13, CardSuit.DIAMOND), c(4, 10, CardSuit.HEART))
        val mv = GdMove.of(cards, level)
        assertNotNull(mv)
        assertEquals(GdType.BOMB, mv!!.type)
        val bomb3 = GdMove.of(listOf(c(9, 3, CardSuit.SPADE), c(10, 3, CardSuit.HEART), c(11, 3, CardSuit.DIAMOND), c(12, 3, CardSuit.CLUB)), level)!!
        assertTrue(mv.beats(bomb3, level))
    }

    // ---------------- 整局模拟（wild-aware AI 不卡局） ----------------

    @Test
    fun `掼蛋整局模拟 AI 对战可正常终局`() {
        repeat(4) { seed ->
            val engine = GuandanEngine(randomSeed = 1900L + seed)
            val infos = (0 until 4).map {
                PlayerInfo(it, "AI$it", 1, true, AiLevel.MEDIUM)
            }
            engine.newMatch(infos)
            var guard = 0
            while (engine.phase != Phase.GAME_OVER) {
                if (++guard > 20000) fail("掼蛋模拟卡死 seed=$seed")
                val actor = engine.currentTurn
                val ai = GuandanAi(actor, AiLevel.MEDIUM, engine.levelRank)
                val ctx = GuandanAi.Ctx(
                    hand = engine.myHand(actor),
                    lastMove = engine.lastMove,
                    lastMoveSeat = engine.lastMoveSeat,
                    handCounts = engine.players.map { it.info.seat to it.hand.size }.toMap(),
                    finishedSeats = engine.players.filter { it.hand.isEmpty() }.map { it.info.seat }
                )
                engine.events.clear()
                val move = ai.chooseMove(ctx)
                val ok = (move != null && engine.play(actor, move)) ||
                        (engine.lastMove != null && engine.pass(actor)) ||
                        engine.play(actor, listOf(engine.myHand(actor).first()))
                assertTrue("AI 无可行动作 seed=$seed turn=$actor", ok)
                if (engine.phase == Phase.GAME_OVER) break
            }
            assertNotNull(engine.result)
            // 等级已提交：下一副级牌 = 赢方新等级
            val r = engine.result!!
            val newLevel = engine.teamLevels.getValue(r.winnerTeam)
            assertTrue(newLevel >= 2)
        }
    }

    // ---------------- 联机网络层（OutboundQueue + 真实 TCP） ----------------

    /** OutboundQueue：快照合并（保留最新状态+累积特效）、关闭语义 */
    @Test
    fun `出站队列快照合并与关闭语义`() {
        val q = OutboundQueue()
        q.offer(NetMsg.ChatBroadcast(1, "hi"))
        val snapA = NetMsg.Snapshot(GameSnapshotHolder.dummy, listOf(NetMsg.Effect("played", 1)))
        q.offer(snapA)
        val snapB = NetMsg.Snapshot(GameSnapshotHolder.dummy, listOf(NetMsg.Effect("bomb", 2)))
        q.offer(snapB)                                  // 队列中已有未发的 snapA → 合并
        val first = q.take()
        assertTrue(first is NetMsg.ChatBroadcast)
        val merged = q.take() as NetMsg.Snapshot
        assertEquals(2, merged.effects.size)            // 特效按序累积不丢
        assertEquals(1, merged.effects[0].seat)
        assertEquals(2, merged.effects[1].seat)
        q.close()
        assertNull(q.take())                            // 关闭后 take 返回 null（写协程退出）
        assertFalse(q.offer(NetMsg.Ping))               // 关闭后 offer 拒绝
    }

    /** 真实 TCP：客人 bid → 房主引擎生效（v19 重构后链路依旧通） */
    @Test
    fun `v19网络重构后客人叫抢链路畅通`() = runBlocking {
        val host = LanHost("房主v19", 0, this)
        host.start()
        delay(400)

        val client = LanClient(this)
        client.connect("127.0.0.1", "客人v19", 1)
        withTimeout(5000) { client.mySeat.first { it >= 0 } }

        host.startGame()
        withTimeout(6000) { client.snapshot.first { it?.snapshot?.phase == Phase.BIDDING } }
        // 重开局直到房主首叫，让客人进入抢地主队列
        var tries = 0
        while (client.snapshot.value?.snapshot?.bidCursor != 0 && tries++ < 30) {
            host.restart()
            withTimeout(6000) {
                client.snapshot.first { it?.snapshot?.phase == Phase.BIDDING && it.snapshot.round > tries }
            }
        }
        host.hostBid(true)
        withTimeout(6000) {
            client.snapshot.first { it?.snapshot?.phase == Phase.ROBBING && it.snapshot.robCursor == 1 }
        }

        client.bid(true)
        withTimeout(6000) {
            host.hostSnapshot.first { (it?.snapshot?.robCount ?: 0) >= 1 }
        }
        assertEquals(1, host.hostSnapshot.value!!.snapshot.robCount)

        host.stop()
        client.disconnect()
    }
}

/** 测试辅助：构造占位快照（只测队列合并语义，不依赖真实引擎状态） */
private object GameSnapshotHolder {
    val dummy = com.laoxiang.ddz.data.GameSnapshot(
        phase = Phase.PLAYING, round = 1, turn = 0, landlord = 0,
        bidCursor = -1, robCursor = -1, bidCandidate = -1, robCount = 0, multiplier = 1,
        bottomCards = emptyList(), bottomHidden = true, lastMoveSeat = -1, lastMove = null,
        seats = emptyList(), result = null
    )
}
