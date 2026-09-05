package com.laoxiang.ddz

import com.laoxiang.ddz.data.*
import org.junit.Assert.*
import org.junit.Test

/**
 * v21 回归：
 * 1) 掼蛋五张同点炸弹识别（ofBase n==5 此前缺 BOMB 分支——用户截图场景：
 *    领出五张10 提示"炸弹×5"却报"不是有效牌型"；压死时可炸是因为
 *    跟牌生成器直构 GdMove/退化 take(4)，识别层漏洞未暴露）
 * 2) 炸弹层级一致性：4炸 < 5炸 < 同花顺 < 6炸，天王炸最大（官方江苏规则）
 * 3) 逢人配 3 同张+双百搭=五炸 的生成/受理恢复（此前展开后同样落入缺失分支被丢弃）
 * 4) 真实引擎链路：领出五炸被受理，AI/提示枚举含五炸整出
 */
class V21FixTest {

    private fun c(id: Int, rank: Int, suit: CardSuit) = Card(id, rank, suit)

    /** 五张10 = 炸弹（用户截图场景：选五张10 点出牌报"不是有效牌型"） */
    @Test
    fun `五张同点识别为炸弹`() {
        val level = 2   // 级牌2，10 为普通牌
        val ten5 = listOf(
            c(1, 10, CardSuit.SPADE), c(2, 10, CardSuit.SPADE), c(3, 10, CardSuit.HEART),
            c(4, 10, CardSuit.CLUB), c(5, 10, CardSuit.DIAMOND)
        )
        val mv = GdMove.of(ten5, level)
        assertNotNull("五张10 必须识别为有效牌型", mv)
        assertEquals(GdType.BOMB, mv!!.type)
        assertEquals(5, mv.length)
        assertEquals(10, mv.mainPower)
    }

    /** 用户场景还原：自由领出五张10 必须可出；压单张2 也必须可出 */
    @Test
    fun `五张炸弹可领出可压单张`() {
        val level = 2
        val ten5 = listOf(
            c(1, 10, CardSuit.SPADE), c(2, 10, CardSuit.SPADE), c(3, 10, CardSuit.HEART),
            c(4, 10, CardSuit.CLUB), c(5, 10, CardSuit.DIAMOND)
        )
        val bomb5 = GdMove.of(ten5, level)!!
        assertTrue("自由领出（last=null）必须可出", bomb5.beats(null, level))
        val single2 = GdMove.of(listOf(c(9, 2, CardSuit.DIAMOND)), level)!!
        assertTrue("五炸必须压单张", bomb5.beats(single2, level))
    }

    /** 炸弹层级：4炸 < 5炸（同点比点力），5炸 < 同花顺 < 6炸，天王炸最大 */
    @Test
    fun `炸弹层级与官方规则一致`() {
        val level = 2
        fun bomb(rank: Int, n: Int, idBase: Int) =
            GdMove.of((1..n).map { c(idBase + it, rank, CardSuit.SPADE) }, level)!!
        val b4 = bomb(3, 4, 100)
        val b5 = bomb(3, 5, 200)
        val b5Big = bomb(4, 5, 300)
        val b6 = bomb(3, 6, 400)
        assertTrue("5炸 > 4炸", b5.beats(b4, level))
        assertFalse("4炸 压不过 5炸", b4.beats(b5, level))
        assertTrue("同张数炸弹比点力", b5Big.beats(b5, level))
        val sf = GdMove.of(
            listOf(c(11, 7, CardSuit.SPADE), c(12, 8, CardSuit.SPADE), c(13, 9, CardSuit.SPADE),
                c(14, 10, CardSuit.SPADE), c(15, 11, CardSuit.SPADE)), level
        )!!
        assertEquals(GdType.STRAIGHT_FLUSH, sf.type)
        assertTrue("同花顺 > 5炸", sf.beats(b5, level))
        assertFalse("5炸 压不过同花顺", b5.beats(sf, level))
        assertTrue("6炸 > 同花顺", b6.beats(sf, level))
        val rocket = GdMove.of(
            listOf(c(21, 16, CardSuit.JOKER), c(22, 16, CardSuit.JOKER),
                c(23, 17, CardSuit.JOKER), c(24, 17, CardSuit.JOKER)), level
        )!!
        assertEquals(GdType.ROCKET, rocket.type)
        assertTrue("天王炸最大", rocket.beats(b6, level))
    }

    /** 压牌枚举：五张10 整出必须出现在候选里（此前只能退化 take(4) 出四炸） */
    @Test
    fun `压牌枚举含五炸整出`() {
        val level = 2
        val hand = listOf(
            c(1, 10, CardSuit.SPADE), c(2, 10, CardSuit.SPADE), c(3, 10, CardSuit.HEART),
            c(4, 10, CardSuit.CLUB), c(5, 10, CardSuit.DIAMOND),
            c(6, 3, CardSuit.SPADE), c(7, 4, CardSuit.HEART)
        )
        val single2 = GdMove.of(listOf(c(9, 2, CardSuit.DIAMOND)), level)!!
        val beats = GuandanAi.genGdBeats(hand, single2, level, withBomb = true)
        assertTrue(
            "候选应含五张10 整出",
            beats.any { it.size == 5 && it.all { x -> x.rank == 10 } }
        )
    }

    /** 逢人配：3 同张 + 双百搭（两张红桃级牌）= 五炸，生成与识别全通 */
    @Test
    fun `逢人配三同张加双百搭成五炸`() {
        val level = 10   // 红桃10 = 逢人配（双副牌共两张）
        val hand = listOf(
            c(1, 13, CardSuit.SPADE), c(2, 13, CardSuit.HEART), c(3, 13, CardSuit.DIAMOND),
            c(4, 10, CardSuit.HEART), c(5, 10, CardSuit.HEART)
        )
        val b4 = GdMove.of(
            listOf(c(9, 3, CardSuit.SPADE), c(10, 3, CardSuit.HEART),
                c(11, 3, CardSuit.DIAMOND), c(12, 3, CardSuit.CLUB)), level
        )!!
        val beats = GuandanAi.genGdBeats(hand, b4, level, withBomb = true)
        val five = beats.firstOrNull { it.size == 5 }
        assertNotNull("3 同张+双百搭应枚举出五炸", five)
        val mv = GdMove.of(five!!, level)
        assertNotNull(mv)
        assertEquals(GdType.BOMB, mv!!.type)
        assertEquals(5, mv.length)
        assertTrue(mv.beats(b4, level))
    }

    /** 五张普通牌型不受新分支影响：三带二/顺子/同花顺照旧，4+1 不成型 */
    @Test
    fun `五张普通牌型不受影响`() {
        val level = 2
        val triopair = GdMove.of(
            listOf(c(1, 5, CardSuit.SPADE), c(2, 5, CardSuit.HEART), c(3, 5, CardSuit.DIAMOND),
                c(4, 8, CardSuit.SPADE), c(5, 8, CardSuit.HEART)), level
        )!!
        assertEquals(GdType.TRIO_PAIR, triopair.type)
        val straight = GdMove.of(
            listOf(c(1, 5, CardSuit.SPADE), c(2, 6, CardSuit.HEART), c(3, 7, CardSuit.DIAMOND),
                c(4, 8, CardSuit.CLUB), c(5, 9, CardSuit.SPADE)), level
        )!!
        assertEquals(GdType.STRAIGHT, straight.type)
        val sf = GdMove.of(
            listOf(c(1, 5, CardSuit.SPADE), c(2, 6, CardSuit.SPADE), c(3, 7, CardSuit.SPADE),
                c(4, 8, CardSuit.SPADE), c(5, 9, CardSuit.SPADE)), level
        )!!
        assertEquals(GdType.STRAIGHT_FLUSH, sf.type)
        assertNull("4+1 不构成任何五张牌型",
            GdMove.of(
                listOf(c(1, 5, CardSuit.SPADE), c(2, 5, CardSuit.HEART), c(3, 5, CardSuit.DIAMOND),
                    c(4, 5, CardSuit.CLUB), c(5, 9, CardSuit.SPADE)), level
            )
        )
    }

    /** 真实引擎链路：领出五张10 被受理，lastMove 变为五炸 */
    @Test
    fun `引擎受理五炸领出`() {
        var eng: GuandanEngine? = null
        for (seed in 1..300) {
            val e = GuandanEngine(randomSeed = seed.toLong())
            e.newMatch((0 until 4).map { PlayerInfo(it, "AI$it", 1, true, AiLevel.EASY) })
            if (e.currentTurn == 0) { eng = e; break }
        }
        assertNotNull("300 个种子里必有一个开局轮到座位0", eng)
        val ten5 = listOf(
            c(1, 10, CardSuit.SPADE), c(2, 10, CardSuit.SPADE), c(3, 10, CardSuit.HEART),
            c(4, 10, CardSuit.CLUB), c(5, 10, CardSuit.DIAMOND)
        )
        eng!!.players.forEach { it.hand.clear() }
        eng.players[0].hand += ten5 + listOf(c(6, 3, CardSuit.SPADE))
        eng.players[1].hand += listOf(c(7, 4, CardSuit.SPADE), c(8, 5, CardSuit.HEART))
        eng.players[2].hand += listOf(c(9, 6, CardSuit.SPADE), c(10, 7, CardSuit.HEART))
        eng.players[3].hand += listOf(c(11, 8, CardSuit.SPADE), c(12, 9, CardSuit.HEART))
        assertTrue("领出五张10 必须被引擎受理", eng.play(0, ten5))
        assertEquals(GdType.BOMB, eng.lastMove!!.type)
        assertEquals(5, eng.lastMove!!.length)
    }
}
