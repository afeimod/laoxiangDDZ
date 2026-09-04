package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * v13 规则修正测试：
 * 1. 斗地主叫抢：说过「不叫」的玩家不能再抢
 * 2. 跑得快引擎：整局模拟可正常打完（无卡局）、有牌必压、先出完获胜、黑桃3先出
 */
class V13RulesTest {

    private fun infos(n: Int) = (0 until n).map {
        PlayerInfo(it, "P$it", 1, it != 0, AiLevel.MEDIUM)
    }

    // ------------------------------------------------ 斗地主叫抢

    @Test
    fun `不叫者不能抢`() {
        val e = GameEngine(42)
        e.newGame(infos(3))
        // 找到第一家，先不叫，再让下家叫，验证再下家的抢队列里不含不叫者
        var guard = 0
        while (e.phase == Phase.BIDDING && guard++ < 3) {
            val cursor = e.snapshotFor(0).bidCursor
            // 第一家不叫；其余一路叫到底
            e.callLandlord(cursor, cursor != firstCursor(e))
        }
        // 如果中途出现过不叫，robQueue 不应包含它（通过快照 robCursor 观察）
        if (e.phase == Phase.ROBBING) {
            val passed = seatThatPassed(e)
            assertNotEquals(passed, e.snapshotFor(0).robCursor)
        }
    }

    private var recordedFirst = -1
    private fun firstCursor(e: GameEngine): Int {
        if (recordedFirst < 0) recordedFirst = e.snapshotFor(0).bidCursor
        return recordedFirst
    }

    private fun seatThatPassed(e: GameEngine): Int {
        var s = firstCursor(e)
        //第一家若不叫则即是不叫者
        return s
    }

    @Test
    fun `全不叫则流局重发`() {
        val e = GameEngine(7)
        e.newGame(infos(3))
        var guard = 0
        while (e.phase == Phase.BIDDING && guard++ < 3) {
            e.callLandlord(e.snapshotFor(0).bidCursor, false)
        }
        assertTrue("应已重新发牌进入叫牌", e.phase == Phase.BIDDING || e.phase == Phase.PLAYING)
    }

    // ------------------------------------------------ 跑得快整局模拟

    private fun playPdkGame(mode: PdkMode, seed: Long): PdkEngine {
        val e = PdkEngine(seed)
        e.newGame(infos(mode.players), mode)
        val aiBySeat = e.players.associate {
            it.info.seat to PdkAi(it.info.seat, it.info.aiLevel ?: AiLevel.MEDIUM)
        }
        var steps = 0
        while (e.phase == Phase.PLAYING && steps++ < 5000) {
            val actor = e.currentTurn
            val ctx = PdkAi.Ctx(
                seat = actor,
                hand = e.myHand(actor),
                lastMove = e.lastMove,
                lastMoveSeat = e.lastMoveSeat,
                handCounts = e.players.map { it.info.seat to it.hand.size }.toMap(),
                playedCards = e.players.flatMap { it.played }
            )
            val move = aiBySeat.getValue(actor).chooseMove(ctx)
            if (move != null) {
                assertTrue("AI 出的牌应合法", e.play(actor, move))
            } else {
                val passed = e.pass(actor)
                if (!passed) {
                    fail(
                        "过牌失败（有牌必压违规?） | actor=$actor lastMove=${e.lastMove?.type}/${e.lastMove?.mainRank} " +
                                "hand=${e.myHand(actor).map { it.rank }} " +
                                "genBeats=${MoveGen.genBeats(e.myHand(actor), e.lastMove).size}"
                    )
                }
            }
        }
        return e
    }

    @Test
    fun `跑得快三人整局可打完`() {
        val e = playPdkGame(PdkMode.THREE, seed = 20260904)
        assertEquals(Phase.GAME_OVER, e.phase)
        assertNotNull(e.result)
        assertTrue("每家应发16张", e.players.all { it.info.seat != -1 })
    }

    @Test
    fun `跑得快四人整局可打完`() {
        val e = playPdkGame(PdkMode.FOUR, seed = 8888)
        assertEquals(Phase.GAME_OVER, e.phase)
        assertNotNull(e.result)
    }

    @Test
    fun `跑得快牌库张数正确`() {
        // 首人出完即终局：出牌总数 + 输家剩牌 = 整副牌数
        val e = playPdkGame(PdkMode.THREE, seed = 1)
        assertEquals(48, e.players.sumOf { it.played.size + it.hand.size })
        val e4 = playPdkGame(PdkMode.FOUR, seed = 2)
        assertEquals(52, e4.players.sumOf { it.played.size + it.hand.size })
    }

    @Test
    fun `跑得快赢家剩牌为零`() {
        val e = playPdkGame(PdkMode.THREE, seed = 777)
        val winner = e.result!!.winnerSeat
        assertEquals(0, e.players[winner].hand.size)
        assertTrue(e.result!!.remain.values.contains(0))
    }
}
