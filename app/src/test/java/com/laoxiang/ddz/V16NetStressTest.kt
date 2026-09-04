package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import com.laoxiang.ddz.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/**
 * v16 联机压力回归：多局对局中，客人（client）在叫抢阶段的每次操作
 * 均按 UI 层同款校验（snapshot.phase / robCursor / bidCursor == mySeat）发起，
 * 检测「轮到客人但点不动 / 引擎拒绝 / 牌局卡住」三类问题。
 * 出牌阶段两个真人座位均用 AiPlayer 决策快速推进。
 */
class V16NetStressTest {

    private fun autoMove(snap: GameSnapshot, seat: Int): List<Card>? {
        val hand = snap.seats.first { it.seat == seat }.hand
        val ctx = AiContext(
            seat = seat, hand = hand, lastMove = snap.lastMove,
            lastMoveSeat = snap.lastMoveSeat, landlord = snap.landlord,
            handCounts = snap.seats.map { it.seat to it.handCount }.toMap(),
            playedCards = emptyList()
        )
        return AiPlayer(seat, AiLevel.MEDIUM).chooseMove(ctx)
    }

    @Test
    fun `多局随机对局客人叫抢均生效且不卡局`() = runBlocking {
        val rnd = Random(20260904)
        val host = LanHost("房主", 0, this)
        host.start()
        delay(400)

        val client = LanClient(this)
        client.connect("127.0.0.1", "客人", 1)
        withTimeout(5000) { client.mySeat.first { it >= 0 } }

        val rounds = 4
        host.startGame()

        repeat(rounds) { roundIdx ->
            // ---- 等 BIDDING（新局）----
            val startRound = client.snapshot.value?.snapshot?.round ?: 0
            withTimeout(15000) {
                client.snapshot.first {
                    it?.snapshot?.phase == Phase.BIDDING && it.snapshot.round >= startRound
                }
            }

            // ---- 叫抢阶段：复刻 UI 校验，轮到谁谁操作（AI 座位由房主自动驱动）----
            var guard = 0
            while (guard++ < 60) {
                val snap = client.snapshot.value?.snapshot
                    ?: host.hostSnapshot.value?.snapshot
                    ?: error("无快照")
                if (snap.phase !in setOf(Phase.BIDDING, Phase.ROBBING)) break
                val actor = if (snap.phase == Phase.BIDDING) snap.bidCursor else snap.robCursor
                assertTrue("叫抢游标异常: $actor", actor in 0..2)

                if (actor == 1) {
                    // 轮到客人：UI 校验（vm.bid 同款）必须放行
                    val uiPass = (snap.phase == Phase.BIDDING && snap.bidCursor == 1) ||
                            (snap.phase == Phase.ROBBING && snap.robCursor == 1)
                    assertTrue("轮到客人但 UI 校验会拒绝（round=$roundIdx guard=$guard）", uiPass)
                    client.bid(rnd.nextBoolean())
                } else if (actor == 0) {
                    host.hostBid(rnd.nextBoolean())
                } else {
                    // AI 座位：等房主自动驱动
                    delay(150)
                    continue
                }
                // 等引擎状态前进（阶段/游标/倍数任一变化）
                withTimeout(5000) {
                    client.snapshot.first { hs ->
                        val s = hs?.snapshot ?: return@first false
                        s.phase != snap.phase ||
                                s.robCursor != snap.robCursor ||
                                s.bidCursor != snap.bidCursor ||
                                s.robCount != snap.robCount
                    }
                }
            }

            val afterBid = client.snapshot.value?.snapshot ?: error("无快照")
            assertTrue("round=$roundIdx 叫抢阶段卡死（仍 phase=${afterBid.phase}）",
                afterBid.phase !in setOf(Phase.BIDDING, Phase.ROBBING))

            // ---- 出牌阶段：两个真人座位用 AiPlayer 决策快速推进 ----
            var playGuard = 0
            var lastStall = ""
            var stallCount = 0
            while (playGuard++ < 600) {
                val s = client.snapshot.value?.snapshot ?: break
                if (s.phase == Phase.GAME_OVER) break
                if (s.phase != Phase.PLAYING) continue

                val stamp = "${s.phase}/${s.turn}/${s.lastMoveSeat}/${s.seats.sumOf { it.handCount }}"
                if (stamp == lastStall) {
                    stallCount++
                    if (stallCount > 50) {
                        fail("round=$roundIdx 出牌卡死: $stamp\n快照=$s")
                    }
                } else {
                    lastStall = stamp
                    stallCount = 0
                }

                when (s.turn) {
                    1 -> {
                        val move = autoMove(s, 1)
                        val ids = move?.map { it.id }
                        if (ids != null) client.play(ids)
                        else if (s.lastMove != null) client.pass()
                        else fail("客人领出但 AI 无解（卡局保护）")
                    }
                    0 -> {
                        val hs = host.hostSnapshot.value?.snapshot ?: break
                        val move = autoMove(hs, 0)
                        if (move != null) host.hostPlay(move.map { it.id })
                        else host.hostPass()
                    }
                    else -> delay(120)   // AI 座位思考中
                }
                delay(60)
            }
            val finalSnap = client.snapshot.value?.snapshot
            assertTrue("round=$roundIdx 对局未正常结束（phase=${finalSnap?.phase}）",
                finalSnap?.phase == Phase.GAME_OVER)

            if (roundIdx < rounds - 1) host.restart()
        }

        host.stop()
        client.disconnect()
    }
}
