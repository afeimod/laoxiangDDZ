package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import com.laoxiang.ddz.net.*
import org.junit.Assert.*
import org.junit.Test

/**
 * 网络协议序列化回环测试：
 * 快照（含手牌/出牌/结果）编码 → JSON → 解码 → 逐字段一致
 */
class ProtocolTest {

    private fun fakeGame(): GameEngine {
        val e = GameEngine(randomSeed = 7)
        e.newGame(
            listOf(
                PlayerInfo(0, "铁柱", 1),
                PlayerInfo(1, "赶集人", 5, true, AiLevel.MEDIUM),
                PlayerInfo(2, "老烟枪", 2, true, AiLevel.HARD)
            )
        )
        // 推进到出牌阶段
        var guard = 0
        while (e.phase in setOf(Phase.BIDDING, Phase.ROBBING) && guard++ < 30) {
            val snap = e.snapshotFor(0)
            val actor = if (e.phase == Phase.BIDDING) snap.bidCursor else snap.robCursor
            if (e.phase == Phase.BIDDING) e.callLandlord(actor, true) else e.robLandlord(actor, false)
        }
        return e
    }

    @Test
    fun `快照序列化回环`() {
        val e = fakeGame()
        assertEquals(Phase.PLAYING, e.phase)
        // 模拟几手出牌
        val landlord = e.landlord
        val hand = e.myHand(landlord)
        val move = MoveGen.genLeads(hand).first()
        assertTrue(e.play(landlord, move.cards))

        val snap = e.snapshotFor(1)   // 以座位1视角
        val msg = NetMsg.Snapshot(snap, listOf(NetMsg.Effect("played", 1)))
        val json = Protocol.encode(msg)
        println("快照 JSON 长度 = ${json.length}")

        val back = Protocol.decode(json)
        assertTrue(back is NetMsg.Snapshot)
        val bs = (back as NetMsg.Snapshot).snapshot
        assertEquals(snap.phase, bs.phase)
        assertEquals(snap.landlord, bs.landlord)
        assertEquals(snap.multiplier, bs.multiplier)
        assertEquals(snap.turn, bs.turn)
        assertEquals(snap.round, bs.round)
        assertEquals(snap.bottomHidden, bs.bottomHidden)
        assertEquals(snap.bottomCards.size, bs.bottomCards.size)
        assertEquals(snap.playedRanks, bs.playedRanks)
        snap.seats.forEachIndexed { i, sv ->
            assertEquals(sv.seat, bs.seats[i].seat)
            assertEquals(sv.name, bs.seats[i].name)
            assertEquals(sv.handCount, bs.seats[i].handCount)
            assertEquals(sv.hand, bs.seats[i].hand)
            assertEquals(sv.lastPlayed, bs.seats[i].lastPlayed)
            assertEquals(sv.lastActionType, bs.seats[i].lastActionType)
        }
        // 隐私：座位1看不到别人手牌
        assertTrue(bs.seats[0].hand.isEmpty() || bs.seats[0].seat == 1)
    }

    @Test
    fun `房间消息回环`() {
        val room = NetMsg.Room(
            seats = listOf(
                NetMsg.SeatInfo(0, "房主", 11, connected = true),
                NetMsg.SeatInfo(1, "翠花", 3, connected = true),
                NetMsg.SeatInfo(2)
            ),
            started = false, aiLevel = 1, hostIp = "192.168.1.8"
        )
        val json = Protocol.encode(room)
        val back = Protocol.decode(json)
        assertTrue(back is NetMsg.Room)
        assertEquals(3, (back as NetMsg.Room).seats.size)
        assertEquals("房主", back.seats[0].name)
        assertFalse(back.started)
    }

    @Test
    fun `聊天与出牌消息回环`() {
        val play = NetMsg.Play(listOf(3, 4, 5))
        val back1 = Protocol.decode(Protocol.encode(play))
        assertEquals((back1 as NetMsg.Play).cardIds, listOf(3, 4, 5))

        val chat = NetMsg.Chat("快点吧，我等到花儿都谢了！", 1)
        val back2 = Protocol.decode(Protocol.encode(chat))
        assertEquals((back2 as NetMsg.Chat).text, "快点吧，我等到花儿都谢了！")

        val ping = NetMsg.Ping
        val back3 = Protocol.decode(Protocol.encode(ping))
        assertTrue(back3 is NetMsg.Ping)
    }

    @Test
    fun `非法JSON安全忽略`() {
        assertNull(Protocol.decode("not a json"))
        assertNull(Protocol.decode("""{"t":"unknown"}"""))
        assertNull(Protocol.decode(""))
    }
}
