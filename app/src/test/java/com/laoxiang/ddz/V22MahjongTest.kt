package com.laoxiang.ddz

import com.laoxiang.ddz.data.AiLevel
import com.laoxiang.ddz.data.MjAi
import com.laoxiang.ddz.data.MjClaimOpt
import com.laoxiang.ddz.data.MjEngine
import com.laoxiang.ddz.data.MjMode
import com.laoxiang.ddz.data.MjPhase
import com.laoxiang.ddz.data.MjRules
import com.laoxiang.ddz.data.MjRules.MjWinShape
import com.laoxiang.ddz.data.MjTile
import com.laoxiang.ddz.data.PlayerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v22 麻将规则测试：胡牌判定 / 癞子 / 番型 / 三模式 AI 全自动对局
 * （牌张守恒 · 分数守恒 · 癞子禁打 · 血战终局 · 查叫花猪）
 */
class V22MahjongTest {

    // ---------- 构牌工具

    private fun tiles(vararg codes: Int): List<MjTile> {
        val used = HashMap<Int, Int>()
        return codes.map { c ->
            val copy = used.getOrDefault(c, 0)
            used[c] = copy + 1
            MjTile(MjTile.idOf(suitOf(c), num = num(c), copy = copy), suitOf(c), num(c))
        }
    }

    private fun suitOf(c: Int) = when {
        c < 9 -> com.laoxiang.ddz.data.MjSuit.WAN
        c < 18 -> com.laoxiang.ddz.data.MjSuit.TONG
        c < 27 -> com.laoxiang.ddz.data.MjSuit.TIAO
        else -> com.laoxiang.ddz.data.MjSuit.ZI
    }

    private fun num(c: Int) = if (c >= 27) c - 26 else c % 9 + 1

    private fun counts(vararg codes: Int) = MjRules.countsOf(tiles(*codes))

    // ---------- 胡牌判定

    @Test
    fun `标准胡牌 - 顺子刻子对子`() {
        assertNotNull(MjRules.canWin(counts(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 17, 17), 0))
        assertNotNull(MjRules.canWin(counts(0, 0, 0, 2, 2, 2, 4, 4, 4, 6, 6, 6, 9, 9), 0))
        assertNull(MjRules.canWin(counts(0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 17, 17), 0))   // 13 张
    }

    @Test
    fun `七对与十三幺`() {
        assertEquals(
            MjWinShape.SEVEN_PAIRS,
            MjRules.canWin(counts(0, 0, 3, 3, 6, 6, 9, 9, 12, 12, 15, 15, 27, 27), 0)?.shape
        )
        assertTrue(MjRules.canWin(counts(0, 0, 0, 0, 3, 3, 6, 6, 9, 9, 12, 12, 27, 27), 0)!!.luxurious)
        assertEquals(
            MjWinShape.THIRTEEN_ORPHANS,
            MjRules.canWin(counts(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33, 33), 0)?.shape
        )
        assertNotNull(MjRules.canWin(counts(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33), 1))
    }

    @Test
    fun `癞子补位`() {
        assertNotNull(MjRules.canWin(counts(0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 17), 2))
        assertNotNull(MjRules.canWin(counts(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), 2))
        assertNotNull(MjRules.canWin(IntArray(34), 14))
        assertNull(MjRules.canWin(IntArray(34), 13))
    }

    @Test
    fun `听牌与向听`() {
        val ting = MjRules.tingCodes(counts(0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 17, 17))
        assertTrue(ting.containsAll(listOf(0, 3, 6, 17)))
        assertEquals(0, MjRules.shanten(counts(0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 17, 17)))
    }

    // ---------- 番型

    @Test
    fun `大众番型`() {
        val qing = MjRules.fanDazhong(
            counts(0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 4, 5, 6, 6), 0, emptyList(),
            tiles(6).first(), selfDraw = false, gangDraw = false, robGang = false,
            lastWall = false, dealerFirst = false, firstDraw = false, laizi = false
        )
        assertEquals(8, qing.total)
        val duidui = MjRules.fanDazhong(
            counts(0, 0, 0, 3, 3, 3, 6, 6, 6, 9, 9, 9, 12, 12), 0, emptyList(),
            tiles(12).first(), selfDraw = true, gangDraw = false, robGang = false,
            lastWall = false, dealerFirst = false, firstDraw = false, laizi = false
        )
        assertEquals(5, duidui.total)   // 对对4 + 自摸1
    }

    @Test
    fun `四川番型`() {
        val pp = MjRules.fanSichuan(
            counts(0, 0, 0, 3, 3, 3, 6, 6, 6, 9, 9, 9, 12, 12), emptyList(),
            tiles(12).first(), gangDraw = false, dealerFirst = false, firstDraw = false
        )
        assertEquals(2, pp.total)
        val gen = MjRules.fanSichuan(
            counts(3, 3, 3, 6, 6, 6, 9, 9, 9, 12, 12),
            listOf(com.laoxiang.ddz.data.MjMeld(com.laoxiang.ddz.data.MjMeldType.GANG_AN, tiles(0, 0, 0, 0))),
            tiles(12).first(), gangDraw = true, dealerFirst = false, firstDraw = false
        )
        assertTrue(gen.total >= 8)   // 对对2 + 杠上花2 + 根×2
    }

    @Test
    fun `川麻缺一门约束`() {
        assertNull(MjRules.canWin(counts(0, 1, 2, 3, 4, 5, 9, 10, 11, 18, 19, 20, 27, 27), 0, requireTwoSuits = true))
        assertNotNull(MjRules.canWin(counts(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 9, 9, 17, 17), 0, requireTwoSuits = true))
    }

    // ---------- 引擎集成

    private fun runGame(mode: MjMode, seed: Long): MjEngine {
        val e = MjEngine(randomSeed = seed)
        val infos = (0 until 4).map {
            PlayerInfo(it, "P$it", it % 12 + 1, it != 0, AiLevel.MEDIUM)
        }
        e.newMatch(infos, mode)
        e.aiSetupHook = {
            for (s in 0 until 4) {
                if (e.canDingque(s)) e.dingqueSuit(s, MjAi(s, AiLevel.MEDIUM, mode).chooseDingque(e.myHand(s)))
            }
            if (e.phase == MjPhase.SWAP3) {
                for (s in 0 until 4) {
                    if (e.canSwap(s)) e.submitSwap(s, MjAi(s, AiLevel.MEDIUM, mode).chooseSwap(e.myHand(s), e.dingque[s]))
                }
            }
        }
        e.aiSetupHook?.invoke()
        var steps = 0
        while (e.phase != MjPhase.GAME_OVER && steps < 200000) {
            steps++
            val pending = e.pendingClaimSeats
            if (pending.isNotEmpty()) {
                for (s in pending) {
                    val opts = e.claimsFor(s)
                    if (opts.isEmpty()) continue
                    val ai = MjAi(s, AiLevel.MEDIUM, mode)
                    val tile = e.currentClaimTile()
                    val opt = tile?.let {
                        ai.chooseClaim(opts, e.myHand(s), e.meldsOf[s], e.dingque[s], it)
                    }
                    assertTrue(e.respondClaim(s, opt))
                }
                continue
            }
            if (e.phase == MjPhase.DINGQUE) {
                for (s in 0 until 4) if (e.canDingque(s)) {
                    e.dingqueSuit(s, MjAi(s, AiLevel.MEDIUM, mode).chooseDingque(e.myHand(s)))
                }
                continue
            }
            if (e.phase == MjPhase.SWAP3) {
                for (s in 0 until 4) if (e.canSwap(s)) {
                    e.submitSwap(s, MjAi(s, AiLevel.MEDIUM, mode).chooseSwap(e.myHand(s), e.dingque[s]))
                }
                continue
            }
            if (e.phase != MjPhase.PLAYING) break
            val turn = e.turn
            if (e.canDiscardPhase(turn)) {
                val ai = MjAi(turn, AiLevel.MEDIUM, mode)
                val act = ai.chooseSelfAction(
                    e.myHand(turn), e.meldsOf[turn], e.dingque[turn],
                    canHu = e.canSelfHu(turn),
                    anGangCodes = e.anGangOptions(turn),
                    buGangTiles = e.buGangOptions(turn)
                )
                when (act.type) {
                    "HU" -> assertTrue(e.declareSelfHu(turn))
                    "GANG_AN" -> assertTrue(e.declareAnGang(turn, act.code))
                    "GANG_BU" -> assertTrue(e.declareBuGang(turn, act.tileId))
                    else -> {
                        val t = act.tileId.takeIf { it >= 0 }
                            ?.let { id -> e.myHand(turn).firstOrNull { it.id == id } }
                            ?: e.discardFirst(turn)
                        assertTrue("无牌可打", t != null && e.discard(turn, t.id))
                    }
                }
                continue
            }
            break
        }
        assertEquals(MjPhase.GAME_OVER, e.phase)
        return e
    }

    @Test
    fun `大众麻将 AI 完整对局 x30`() = repeat(30) { i ->
        val e = runGame(MjMode.DAZHONG, seed = i * 17L)
        val all = (0 until 4).flatMap { e.myHand(it) } + e.rivers.flatMap { it } +
                e.meldsOf.flatMap { m -> m.flatMap { it.tiles } } + e.wallTiles()
        assertEquals(136, all.size)
        assertEquals(136, all.map { it.id }.toSet().size)
        assertEquals(0, e.result!!.scoreDelta.values.sum())
    }

    @Test
    fun `癞子麻将 AI 完整对局 x30 且癞子不被打出`() = repeat(30) { i ->
        val e = runGame(MjMode.LAIZI, seed = i * 19L + 1)
        val all = (0 until 4).flatMap { e.myHand(it) } + e.rivers.flatMap { it } +
                e.meldsOf.flatMap { m -> m.flatMap { it.tiles } } + e.wallTiles()
        assertEquals(136, all.size)
        assertTrue(e.rivers.none { r -> r.any { it.code == 31 } })
        assertEquals(0, e.result!!.scoreDelta.values.sum())
    }

    @Test
    fun `四川血战 AI 完整对局 x30 定缺换三张查叫`() = repeat(30) { i ->
        val e = runGame(MjMode.SICHUAN, seed = i * 23L + 2)
        val all = (0 until 4).flatMap { e.myHand(it) } + e.rivers.flatMap { it } +
                e.meldsOf.flatMap { m -> m.flatMap { it.tiles } } + e.wallTiles()
        assertEquals(108, all.size)
        assertEquals(108, all.map { it.id }.toSet().size)
        assertEquals(0, e.result!!.scoreDelta.values.sum())
        // 定缺全部完成
        assertTrue(e.dingque.all { it in 0..2 })
        // 无吃（四川不许吃）
        assertTrue(e.meldsOf.all { m -> m.none { it.type == com.laoxiang.ddz.data.MjMeldType.CHI } })
    }

    @Test
    fun `引擎快照隐私 - 他人手牌不泄露`() {
        val e = MjEngine(randomSeed = 99)
        val infos = (0 until 4).map { PlayerInfo(it, "P$it", 1, it != 0, AiLevel.MEDIUM) }
        e.newMatch(infos, MjMode.DAZHONG)
        val snap = e.snapshotFor(1)
        snap.seats.forEach { sv ->
            if (sv.seat != 1) assertTrue(sv.hand.isEmpty())
        }
        assertEquals(13, snap.seats[1].hand.size)
        assertNull(snap.seats[2].dingque.takeIf { e.phase == MjPhase.DINGQUE })
    }
}
