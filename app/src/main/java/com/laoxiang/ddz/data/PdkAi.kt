package com.laoxiang.ddz.data

import kotlin.random.Random

/**
 * 跑得快 AI（三档难度）
 *
 * 规则约束：有牌必压 —— 桌上有牌且自己有能压的招时必须出（chooseMove 不会返回 null）。
 * 策略：
 * · 能一手出完 → 直接出
 * · 领出：优先小组合消耗；对手剩 1 张绝不送单；两手清时先出对手压不住的一手
 * · 压牌：最便宜且不拆结构；2/炸弹看时机（对手快跑完或自己两手内才动用）
 */
class PdkAi(
    val seat: Int,
    val level: AiLevel,
    private val seed: Long? = null
) {
    private val rng = seed?.let { Random(it) } ?: Random.Default

    /** 跑得快决策上下文 */
    data class Ctx(
        val seat: Int,
        val hand: List<Card>,
        val lastMove: Move?,
        val lastMoveSeat: Int,
        val handCounts: Map<Int, Int>,
        val playedCards: List<Card>
    )

    /** 返回要出的牌；仅当桌上无牌可压（或领出不可能）时返回 null */
    fun chooseMove(ctx: Ctx): List<Card>? {
        val hand = ctx.hand
        if (hand.isEmpty()) return null

        // 一手清 → 直接赢
        val finishNow = if (ctx.lastMove == null) {
            MoveGen.genLeads(hand).firstOrNull { it.cards.size == hand.size }
        } else {
            MoveGen.genBeats(hand, ctx.lastMove).firstOrNull { it.cards.size == hand.size }
        }
        if (finishNow != null) return finishNow.cards

        return when (level) {
            AiLevel.EASY -> easy(ctx)
            AiLevel.MEDIUM -> medium(ctx)
            AiLevel.HARD -> hard(ctx)
        }
    }

    // ------------------------------------------------ 简单

    private fun easy(ctx: Ctx): List<Card>? {
        val hand = ctx.hand
        if (ctx.lastMove == null) {
            val leads = MoveGen.genLeads(hand).filter { !it.type.isBombLike }
            if (leads.isEmpty()) return hand.take(1)
            return leads[rng.nextInt(leads.size)].cards
        }
        val beats = MoveGen.genBeats(hand, ctx.lastMove)
        if (beats.isEmpty()) return null
        val plain = beats.filter { !it.type.isBombLike }
        return (plain.ifEmpty { beats })[rng.nextInt((plain.ifEmpty { beats }).size)].cards
    }

    // ------------------------------------------------ 中等

    private fun medium(ctx: Ctx): List<Card>? {
        val hand = ctx.hand
        if (ctx.lastMove == null) return lead(ctx, useMemory = false)

        val beats = MoveGen.genBeats(hand, ctx.lastMove)
        if (beats.isEmpty()) return null
        val nonBomb = beats.filter { it.type.isBombLike.not() }

        // 压牌方快跑完 → 拼命压（可用炸弹）
        if ((ctx.handCounts[ctx.lastMoveSeat] ?: 20) <= 2) return beats.last().cards

        // 有牌必压：只剩炸弹能压时也必须出（跑得快规则）
        if (nonBomb.isEmpty()) return beats.first().cards
        return nonBomb.minWithOrNull(
            compareBy({ splitPenalty(it, hand) }, { it.mainRank })
        )?.cards
    }

    // ------------------------------------------------ 困难

    private fun hard(ctx: Ctx): List<Card>? {
        val hand = ctx.hand
        if (ctx.lastMove == null) return lead(ctx, useMemory = true)

        val beats = MoveGen.genBeats(hand, ctx.lastMove)
        if (beats.isEmpty()) return null
        val nonBomb = beats.filter { !it.type.isBombLike }
        val unseen = unseenCounts(ctx)

        // 出牌方只剩 1 张 → 必须顶到最大（含炸弹）
        if ((ctx.handCounts[ctx.lastMoveSeat] ?: 20) <= 1) return beats.last().cards

        // 有牌必压：只剩炸弹能压时也必须出
        if (nonBomb.isEmpty()) {
            val meHands = HandDecompose.decompose(hand).size
            val oppLow = ctx.handCounts.filter { it.key != ctx.seat }.values.minOrNull() ?: 20
            return if (meHands <= 3 || oppLow <= 5) beats.last().cards
            else beats.first().cards
        }

        // 有非炸弹可压：最便宜且不拆结构
        val cheap = nonBomb.minWithOrNull(
            compareBy({ splitPenalty(it, hand) }, { it.mainRank })
        )!!
        // 压完无人能反压且自己剩牌少 → 放心走
        val safe = !existsBeatInUnseen(cheap, unseen)
        val meHands = HandDecompose.decompose(hand).size
        if (safe && meHands <= 4) return cheap.cards
        // 大牌管理：中局不用 2/炸弹跟小牌
        if (cheap.mainRank >= 15 && meHands > 4 &&
            (ctx.handCounts[ctx.lastMoveSeat] ?: 20) > 6
        ) {
            // 忍 —— 但仍必须有牌必压：找更便宜的替代，没有就出
            val alt = nonBomb.filter { it.mainRank < 15 }
                .minByOrNull { it.mainRank }
            return (alt ?: cheap).cards
        }
        return cheap.cards
    }

    // ------------------------------------------------ 领出

    private fun lead(ctx: Ctx, useMemory: Boolean): List<Card>? {
        val hand = ctx.hand
        val leads = MoveGen.genLeads(hand)
        if (leads.isEmpty()) return null
        val safe = leads.filter { !it.type.isBombLike }
            .ifEmpty { return leads.first().cards }

        val combos = HandDecompose.decompose(hand)

        // 两手清：先出对手压不住的一手；都压得住 → 先出大的
        if (combos.size <= 2) {
            if (useMemory) {
                val unseen = unseenCounts(ctx)
                val unbeat = combos.map { it.move }.filter { !existsBeatInUnseen(it, unseen) }
                if (unbeat.isNotEmpty()) return unbeat.minByOrNull { it.mainRank }!!.cards
                return combos.maxByOrNull { it.move.mainRank }!!.move.cards
            }
            return combos.maxByOrNull { it.move.mainRank }!!.move.cards
        }

        // 对手剩 1 张 → 绝不送单
        if (ctx.handCounts.filter { it.key != ctx.seat }.any { it.value <= 1 }) {
            safe.firstOrNull { it.type != MoveType.SINGLE }?.let { return it.cards }
        }

        // 困难：优先走对手大概率压不住的多张组合
        if (useMemory) {
            val unseen = unseenCounts(ctx)
            val likelySafe = safe.firstOrNull { it.cards.size >= 4 && !existsBeatInUnseen(it, unseen) }
            if (likelySafe != null) return likelySafe.cards
        }
        return safe.first().cards
    }

    // ------------------------------------------------ 工具

    /** 拆结构惩罚（同斗地主 AI） */
    private fun splitPenalty(move: Move, hand: List<Card>): Int {
        if (move.type.isBombLike) return 0
        val handCounts = hand.groupBy { it.rank }.mapValues { it.value.size }
        val used = move.cards.groupBy { it.rank }.mapValues { it.value.size }
        var penalty = 0
        used.forEach { (r, u) ->
            val left = (handCounts[r] ?: 0) - u
            if (left > 0) penalty += when (left) { 1 -> 2; 2 -> 4; 3 -> 8; else -> 12 }
        }
        return penalty
    }

    private fun unseenCounts(ctx: Ctx): Map<Int, Int> {
        val played = ctx.playedCards.groupBy { it.rank }.mapValues { it.value.size }
        val mine = ctx.hand.groupBy { it.rank }.mapValues { it.value.size }
        return (3..15).associateWith { r -> 4 - (played[r] ?: 0) - (mine[r] ?: 0) }
    }

    private fun existsBeatInUnseen(candidate: Move, unseen: Map<Int, Int>): Boolean {
        return when (candidate.type) {
            MoveType.SINGLE -> (candidate.mainRank + 1..15).any { (unseen[it] ?: 0) > 0 }
            MoveType.PAIR -> (candidate.mainRank + 1..15).any { (unseen[it] ?: 0) >= 2 }
            else -> (candidate.mainRank + 1..15).any { (unseen[it] ?: 0) >= 3 } ||
                    unseen.entries.any { it.value >= 4 }
        }
    }
}
