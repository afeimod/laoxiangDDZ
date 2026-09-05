package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import com.laoxiang.ddz.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test

/**
 * v20 全系列联机回归（真实 TCP）：
 * GameHost（掼蛋/跑得快/锄大地/升级驱动）+ LanClient 加入 → 开局 → 客人操作 → 房主引擎生效。
 *
 * 出牌链路设计为确定性的：重试开局直到「客人座位为首出位」，客人立即首出一张，
 * 断言其手牌数下降（消息 客人→房主→引擎→广播→客人 全链路生效）。
 * 客人的过牌路径在等待窗口内自然覆盖；升级无过牌、每轮必出牌，走完整对局链路。
 */
class V20NetGamesTest {

    private fun decodeOf(m: NetMsg.GSnapshot): Any = when (m.game) {
        "guandan" -> netJson.decodeFromJsonElement(GdSnapshot.serializer(), m.payload)
        "pdk" -> netJson.decodeFromJsonElement(PdkSnapshot.serializer(), m.payload)
        "bigtwo" -> netJson.decodeFromJsonElement(BtSnapshot.serializer(), m.payload)
        "shengji" -> netJson.decodeFromJsonElement(SjSnapshot.serializer(), m.payload)
        else -> throw IllegalArgumentException(m.game)
    }

    private fun NetMsg.GSnapshot.decoded(): Any? = runCatching { decodeOf(this) }.getOrNull()

    private fun hostOf(game: String, seats: Int, scope: CoroutineScope): GameHost =
        GameHost("房主老王", 0, seats, scope) { gs, trig ->
            GameDrivers.create(game, gs, trig, seats)
        }

    // ------------------------------------------------ 协议

    @Test
    fun `通用快照消息编解码往返`() {
        val snap = PdkSnapshot(
            mode = PdkMode.THREE, phase = Phase.PLAYING, round = 1,
            turn = 2, lastMoveSeat = 1, lastMove = null,
            seats = listOf(PdkSeatView(0, "甲", 1, false, false, 16)),
            playedRanks = mapOf(3 to 1), result = null
        )
        val payload = netJson.encodeToJsonElement(PdkSnapshot.serializer(), snap)
        val msg = NetMsg.GSnapshot("pdk", payload, listOf(NetMsg.Effect("played", seat = 1)))
        val back = Protocol.decode(Protocol.encode(msg)) as NetMsg.GSnapshot
        assertEquals("pdk", back.game)
        assertEquals("played", back.effects.first().type)
        val rt = netJson.decodeFromJsonElement(PdkSnapshot.serializer(), back.payload)
        assertEquals(Phase.PLAYING, rt.phase)
        assertEquals(2, rt.turn)
        assertEquals(1, rt.playedRanks[3])
    }

    // ------------------------------------------------ 通用：重试到客人首出 → 客人出牌 → 手牌下降

    private suspend fun clientLeadOnce(
        scope: CoroutineScope,
        game: String,
        seats: Int,
        initialHand: Int,
        firstMove: (Any, Int) -> List<Int>
    ): Boolean {
        var tries = 0
        while (tries++ < 12) {
            val host = hostOf(game, seats, CoroutineScope(Dispatchers.Default))
            host.start()
            delay(400)
            val client = LanClient(scope)
            var ok = false
            try {
                client.connect("127.0.0.1", "客人$tries", 3 + tries)
                withTimeout(5000) { client.mySeat.first { it >= 0 } }
                val meSeat = client.mySeat.value
                host.startGame()
                withTimeout(8000) {
                    client.gSnapshot.first {
                        it?.game == game && (it.decoded() as? SjSnapshot ?: it.decoded()) != null
                    }
                }
                val any = client.gSnapshot.value!!.decoded()!!
                val turn = when (val s = any) {
                    is GdSnapshot -> s.turn
                    is PdkSnapshot -> s.turn
                    is BtSnapshot -> s.turn
                    is SjSnapshot -> s.turn
                    else -> -2
                }
                val firstLead = when (val s = any) {
                    is GdSnapshot -> true    // 掼蛋首出无牌面约束
                    is PdkSnapshot -> s.playedRanks.values.all { it == 0 }
                    is BtSnapshot -> s.playedRanks.values.all { it == 0 }
                    else -> false
                }
                val playing = when (val s = any) {
                    is GdSnapshot -> s.phase == Phase.PLAYING
                    is PdkSnapshot -> s.phase == Phase.PLAYING
                    is BtSnapshot -> s.phase == Phase.PLAYING
                    else -> false
                }
                if (playing && turn == meSeat && firstLead) {
                    // 客人首出第一手
                    client.play(firstMove(any, meSeat))
                    withTimeout(15000) {
                        client.gSnapshot.first { m ->
                            val s = m?.decoded()
                            val cnt = when (s) {
                                is GdSnapshot -> s.seats.first { it.seat == meSeat }.handCount
                                is PdkSnapshot -> s.seats.first { it.seat == meSeat }.handCount
                                is BtSnapshot -> s.seats.first { it.seat == meSeat }.handCount
                                else -> initialHand
                            }
                            cnt < initialHand
                        }
                    }
                    ok = true
                }
            } catch (e: Exception) {
                // 该轮没赶上首出位/超时 → 换下一局
            } finally {
                client.disconnect()
                host.stop()
            }
            if (ok) return true
            delay(300)   // 让端口 TIME_WAIT 缓一缓
        }
        return false
    }

    // ------------------------------------------------ 掼蛋

    @Test
    fun `掼蛋联机：客人首出并生效`() = runBlocking {
        val ok = clientLeadOnce(this, "guandan", 4, 27) { any, meSeat ->
            val s = any as GdSnapshot
            val hand = s.seats.first { it.seat == meSeat }.hand
            listOf(hand.last().id)   // 领出最小单张
        }
        assertTrue("掼蛋客人首出未生效（12 次重试都没赶上首出位）", ok)
    }

    // ------------------------------------------------ 跑得快（三人）

    @Test
    fun `跑得快联机：三人局客人首出黑桃三`() = runBlocking {
        val ok = clientLeadOnce(this, "pdk", 3, 16) { any, meSeat ->
            val s = any as PdkSnapshot
            val hand = s.seats.first { it.seat == meSeat }.hand
            listOf(hand.first { it.rank == 3 && it.suit == CardSuit.SPADE }.id)
        }
        assertTrue("跑得快客人首出未生效", ok)
    }

    // ------------------------------------------------ 锄大地

    @Test
    fun `锄大地联机：客人首出方块三`() = runBlocking {
        val ok = clientLeadOnce(this, "bigtwo", 4, 13) { any, meSeat ->
            val s = any as BtSnapshot
            val hand = s.seats.first { it.seat == meSeat }.hand
            listOf(hand.first { it.rank == 3 && it.suit == CardSuit.DIAMOND }.id)
        }
        assertTrue("锄大地客人首出未生效", ok)
    }

    // ------------------------------------------------ 升级（定主 → 扣底 → 出牌全链路）

    @Test
    fun `升级联机：定主扣底出牌全链路`() = runBlocking {
        var stage = "start"
        val wd = launch {
            val t0 = System.currentTimeMillis()
            while (true) {
                delay(5000)
                val snap = clientRef?.gSnapshot?.value?.decoded() as? SjSnapshot
                println("WD: t=${(System.currentTimeMillis() - t0) / 1000}s stage=$stage phase=${snap?.phase} turn=${snap?.turn} hand=${snap?.seats?.firstOrNull { it.seat == clientSeat }?.handCount}")
            }
        }
        var stageRef = { s: String -> stage = s }
        stageRef("connecting")
        val host = hostOf("shengji", 4, this)
        val client = LanClient(this)
        try {
            host.start()
            delay(400)
            client.connect("127.0.0.1", "升级客人", 4)
            withTimeout(5000) { client.mySeat.first { it >= 0 } }
            val meSeat = client.mySeat.value
            clientSeat = meSeat
            clientRef = client
            stageRef("wait-snapshot")
            host.startGame()
            withTimeout(8000) { client.gSnapshot.first { it?.game == "shengji" && it.decoded() != null } }
            stageRef("wait-bidding")
        withTimeout(8000) {
            client.gSnapshot.first {
                it?.game == "shengji" && (it.decoded() as? SjSnapshot)?.phase == Phase.BIDDING
            }
        }
        delay(1500)

        // 2) 房主收口定主 → BURYING
        stageRef("settle")
        host.hostSettle()
        stageRef("wait-burying")
        withTimeout(8000) {
            client.gSnapshot.first {
                it?.game == "shengji" && (it.decoded() as? SjSnapshot)?.phase == Phase.BURYING
            }
        }
        val burying = client.gSnapshot.value!!.decoded() as SjSnapshot

        // 3) 客人庄 → 扣 8 张；AI 庄由驱动自动扣；房主庄 → 自动扣
        when (burying.dealer) {
            meSeat -> {
                val hand = burying.seats.first { it.seat == meSeat }.hand
                assertEquals("庄家捡底后 33 张", 33, hand.size)
                client.play(hand.sortedBy { it.rank }.take(8).map { it.id })
            }
            0 -> host.hostAutoBury()
            else -> {}
        }

        // 4) 出牌阶段：升级无过牌，双端每轮必出；等客人手牌 < 25
        stageRef("playing")
        val deadline = System.currentTimeMillis() + 90_000
        var acted = false
        while (System.currentTimeMillis() < deadline && !acted) {
            val snap = client.gSnapshot.value?.decoded() as? SjSnapshot
            if (snap == null || snap.phase != Phase.PLAYING) { delay(150); continue }
            if (snap.seats.first { it.seat == meSeat }.handCount < 25) { acted = true; break }
            when (snap.turn) {
                meSeat -> {
                    val hand = snap.seats.first { it.seat == meSeat }.hand
                    if (hand.isEmpty()) break
                    val trump = snap.trumpSuit
                    val lr = snap.levelRank
                    val isTrump: (Card) -> Boolean = { c -> SjRules.isTrump(c, trump!!, lr) }
                    val ids: List<Int> = if (snap.trickPlays.isEmpty()) {
                        listOf(hand.last().id)
                    } else {
                        val ledCards = snap.trickPlays.first().second
                        val n = ledCards.size
                        val ledSuit = if (ledCards.all(isTrump)) CardSuit.JOKER else ledCards[0].suit
                        val pool = if (ledSuit == CardSuit.JOKER) hand.filter(isTrump)
                        else hand.filter { it.suit == ledSuit && !isTrump(it) }
                        val rest = hand.filter { c -> pool.none { it.id == c.id } }.sortedBy { it.rank }
                        (pool.sortedBy { it.rank } + rest).take(n).map { it.id }
                    }
                    client.play(ids)
                    delay(300)
                }
                0 -> {
                    // 房主（真人位）由测试代打：必须用房主自己的快照（含座位0手牌）
                    val hs = host.hostSnapshot.value?.decoded() as? SjSnapshot
                    val hand = hs?.seats?.first { it.seat == 0 }?.hand
                    if (hs != null && hs.phase == Phase.PLAYING && hs.turn == 0 && !hand.isNullOrEmpty()) {
                        val trump = hs.trumpSuit
                        val lr = hs.levelRank
                        val isTrump: (Card) -> Boolean = { c -> SjRules.isTrump(c, trump!!, lr) }
                        val ids: List<Int> = if (hs.trickPlays.isEmpty()) {
                            listOf(hand.last().id)
                        } else {
                            val ledCards = hs.trickPlays.first().second
                            val n = ledCards.size
                            val ledSuit = if (ledCards.all(isTrump)) CardSuit.JOKER else ledCards[0].suit
                            val pool = if (ledSuit == CardSuit.JOKER) hand.filter(isTrump)
                            else hand.filter { it.suit == ledSuit && !isTrump(it) }
                            val rest = hand.filter { c -> pool.none { it.id == c.id } }.sortedBy { it.rank }
                            (pool.sortedBy { it.rank } + rest).take(n).map { it.id }
                        }
                        host.hostPlay(ids)
                    }
                    delay(400)
                }
                else -> delay(150)
            }
        }
        assertTrue("客人未能在升级出牌阶段出牌", acted)
        wd.cancel()
        } finally {
            runCatching { host.stop() }
            runCatching { client.disconnect() }
        }
    }

    @Volatile private var clientRef: LanClient? = null

    @Volatile private var clientSeat: Int = 1
}
