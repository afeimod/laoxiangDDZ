package com.laoxiang.ddz

import com.laoxiang.ddz.data.AiLevel
import com.laoxiang.ddz.data.Card
import com.laoxiang.ddz.data.CardSuit
import com.laoxiang.ddz.data.GdMove
import com.laoxiang.ddz.data.GdRules
import com.laoxiang.ddz.data.GuandanEngine
import com.laoxiang.ddz.data.Phase
import com.laoxiang.ddz.data.PlayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v21 掼蛋判圈回归（用户反馈：出过牌就立刻继续出牌，不等下家压牌）：
 * 官方规则（维基/江苏省掼蛋竞赛规则）——一圈内各家依次压牌或过牌，过牌只表示不压当前这一手；
 * 有人出更大的牌后，先前过牌者会被再次询问；直到最后出牌之后其余各家均过牌（出牌轮转回到
 * 最后出牌者）该圈才结束，由其领出下一圈。
 * v20 的「每人一圈只行动一次」模型导致：甲领出→乙过→丙压→丁行动完即收圈，丙直接继续领出，
 * 乙手里有更大的牌也再也要不到。
 */
class V21RulesTest {

    private var idSeq = 0
    private fun c(rank: Int, suit: CardSuit): Card =
        Card(rank * 1000 + suit.ordinal * 100 + (idSeq++), rank, suit)

    private fun infos4() = (0 until 4).map {
        PlayerInfo(it, "P$it", 1, true, AiLevel.MEDIUM)
    }

    private fun engineWithHands(seed: Long, hands: List<List<Card>>): GuandanEngine {
        val e = GuandanEngine(seed)
        e.newMatch(infos4())
        e.players.forEachIndexed { i, p ->
            p.hand.clear()
            p.hand += hands[i]
        }
        return e
    }

    @Test
    fun `圈内被压牌后先前过牌者须被再次询问`() {
        // 座位：P1 领出 3♠；P2(K♠) 先过；P3 用 4♠ 压；P0(3♥) 压不过过牌。
        // 正确规则：P0 过后须回到 P1（领出者）再问 → 再到 P2（先前过牌、持有 K♠）再问；
        // v20 旧模型在 P0 过牌后即收圈并由 P3 继续领出，P2 的 K♠ 再也要不到。
        // 注意：本副级牌为 2（power=16），故小牌均取 3 点，避开级牌干扰终局走向。
        val s3 = c(3, CardSuit.SPADE); val s13 = c(13, CardSuit.SPADE)
        val s4 = c(4, CardSuit.SPADE)
        val h3 = c(3, CardSuit.HEART)
        val hands = listOf(
            listOf(h3),                          // P0：压不过 3♠/4♠
            listOf(s3, c(3, CardSuit.DIAMOND)),  // P1：领出 3♠
            listOf(s13, c(3, CardSuit.CLUB)),    // P2：K♠ 可压 4♠
            listOf(s4, c(6, CardSuit.SPADE))     // P3：4♠ 压 3♠
        )
        var eng: GuandanEngine? = null
        for (seed in 0L until 200L) {
            val cand = engineWithHands(seed, hands)
            if (cand.snapshotFor(0).turn == 1) { eng = cand; break }
        }
        assertNotNull("200 个种子内未找到 P1 先出（发牌随机数异常）", eng)
        val e = eng!!

        // P1 领出 3♠ → 依次 P2、P3、P0
        assertTrue(e.play(1, listOf(s3)))
        assertEquals(2, e.snapshotFor(0).turn)
        // P2 选择先过（持有 K♠）
        assertTrue(e.pass(2))
        assertEquals(3, e.snapshotFor(0).turn)
        // P3 用 4♠ 压
        assertTrue(e.play(3, listOf(s4)))
        assertEquals(0, e.snapshotFor(0).turn)
        // P0 压不过，过
        assertTrue(e.pass(0))
        // ★ 核心断言 1：回到领出者 P1 再次询问（v20 此处已收圈由 P3 领出）
        assertEquals(1, e.snapshotFor(0).turn)
        assertTrue(e.pass(1))   // P1 的 2♣ 压不过 4♠
        // ★ 核心断言 2：先前过牌的 P2 被再次询问，K♠ 仍可压（用户反馈的场景）
        assertEquals(2, e.snapshotFor(0).turn)
        assertEquals(3, e.snapshotFor(0).lastMoveSeat)   // 桌面仍是 P3 的 4♠
        assertTrue(e.play(2, listOf(s13)))               // P2 出 K♠
        // 一圈继续：P3(6♠)、P0、P1 依次被询问均过 → 轮转回到 P2 收圈
        assertEquals(3, e.snapshotFor(0).turn)
        assertTrue(e.pass(3))
        assertEquals(0, e.snapshotFor(0).turn)
        assertTrue(e.pass(0))
        assertEquals(1, e.snapshotFor(0).turn)
        assertTrue(e.pass(1))
        // 圈结束：P2 获得领出权，桌面清空；后续 P2(3♣)→P3(6♠ 压)→P2 头游、P3 二游、P1 三游（异队升 1 级）
        assertEquals(2, e.snapshotFor(0).turn)
        assertEquals(null, e.snapshotFor(0).lastMove)

        // 继续打完本副：结算不受影响（P2 头游；非双下三游与头游异队 → 升 1 级）
        var guard = 0
        while (e.phase == Phase.PLAYING && guard++ < 200) {
            val snap = e.snapshotFor(0)
            val turn = snap.turn
            val hand = e.players[turn].hand
            val last = snap.lastMove
            if (last == null) {
                assertTrue(
                    e.play(turn, listOf(hand.minBy { GdRules.power(it.rank, e.levelRank) }))
                )
            } else {
                val beat = hand.filter {
                    GdMove.of(listOf(it), e.levelRank)?.beats(last, e.levelRank) == true
                }
                if (beat.isNotEmpty()) {
                    assertTrue(
                        e.play(
                            turn,
                            listOf(beat.minBy { GdRules.power(it.rank, e.levelRank) })
                        )
                    )
                } else {
                    assertTrue(e.pass(turn))
                }
            }
        }
        assertEquals(Phase.GAME_OVER, e.phase)
        val r = e.result!!
        assertEquals(2, r.headSeat)
        assertEquals(3, r.secondSeat)
        assertEquals(1, r.upgrade)
    }
}
