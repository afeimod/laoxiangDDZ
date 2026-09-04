package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import com.laoxiang.ddz.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/**
 * v16 联机回归：加入对局的客人（client）在叫抢地主阶段的完整链路
 * client.bid → TCP → 房主引擎 callLandlord/robLandlord → 快照广播回客户端
 */
class V16NetBidTest {

    /** 房主先叫地主 → 轮到客人抢 → 客人点「抢地主」必须生效 */
    @Test
    fun `客人抢地主消息被房主引擎接受`() = runBlocking {
        val host = LanHost("房主老张", 0, this)
        host.start()
        delay(400)   // ServerSocket 监听就绪

        val client = LanClient(this)
        client.connect("127.0.0.1", "加入的客人", 1)
        withTimeout(5000) { client.mySeat.first { it >= 0 } }
        assertEquals(1, client.mySeat.value)

        // 重开局直到首叫权在房主（bidCursor==0），保证客人进入抢地主队列
        var tries = 0
        host.startGame()
        withTimeout(6000) {
            client.snapshot.first { it?.snapshot?.phase == Phase.BIDDING }
        }
        while (client.snapshot.value?.snapshot?.bidCursor != 0 && tries++ < 30) {
            host.restart()
            withTimeout(6000) {
                client.snapshot.first {
                    it?.snapshot?.phase == Phase.BIDDING && it.snapshot.round > tries
                }
            }
        }
        assertEquals("30 次重开局都没轮到房主首叫（概率不可能）", 0, client.snapshot.value?.snapshot?.bidCursor)

        // 房主叫地主 → 抢地主队列 = [客人(1), 电脑(2)]
        host.hostBid(true)
        withTimeout(6000) {
            client.snapshot.first {
                it?.snapshot?.phase == Phase.ROBBING && it.snapshot.robCursor == 1
            }
        }

        // === 关键动作：客人点「抢地主」 ===
        client.bid(true)

        // 房主引擎视角：robCount 必须 = 1、候选地主 = 客人座位 1
        withTimeout(6000) {
            host.hostSnapshot.first { (it?.snapshot?.robCount ?: 0) >= 1 }
        }
        val after = host.hostSnapshot.value!!.snapshot
        assertEquals("客人抢地主未生效（robCount!=1）", 1, after.robCount)
        assertEquals("抢地主者座位不是客人", 1, after.bidCandidate)

        host.stop()
        client.disconnect()
    }

    /** 客人首个叫地主（bidCursor==1）→ 房主引擎接受并进入抢地主阶段 */
    @Test
    fun `客人叫地主消息被房主引擎接受`() = runBlocking {
        val host = LanHost("房主老李", 0, this)
        host.start()
        delay(400)

        val client = LanClient(this)
        client.connect("127.0.0.1", "加入的客人", 2)
        withTimeout(5000) { client.mySeat.first { it >= 0 } }

        // 重开局直到首叫权在客人（bidCursor==1）
        var tries = 0
        host.startGame()
        withTimeout(6000) {
            client.snapshot.first { it?.snapshot?.phase == Phase.BIDDING }
        }
        while (client.snapshot.value?.snapshot?.bidCursor != 1 && tries++ < 30) {
            host.restart()
            withTimeout(6000) {
                client.snapshot.first {
                    it?.snapshot?.phase == Phase.BIDDING && it.snapshot.round > tries
                }
            }
        }
        assertEquals(1, client.snapshot.value?.snapshot?.bidCursor)

        // === 客人叫地主 ===
        client.bid(true)

        withTimeout(6000) {
            host.hostSnapshot.first {
                (it?.snapshot?.bidCandidate ?: -1) == 1 &&
                        it?.snapshot?.phase in setOf(Phase.ROBBING, Phase.PLAYING)
            }
        }
        val after = host.hostSnapshot.value!!.snapshot
        assertEquals("客人叫地主未生效", 1, after.bidCandidate)
        assertEquals("客人首叫后应立即进入抢地主阶段（下家是真人房主）", Phase.ROBBING, after.phase)

        host.stop()
        client.disconnect()
    }
}
