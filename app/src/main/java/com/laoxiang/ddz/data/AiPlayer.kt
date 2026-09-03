package com.laoxiang.ddz.data

import kotlin.random.Random

/**
 * 斗地主 AI（三档难度）
 *
 * EASY   —— 随机散漫：叫牌随缘、出牌乱走、常放过
 * MEDIUM —— 稳健贪小：最小代价压牌、不拆炸弹、配合队友粗略
 * HARD   —— 老谋深算：记牌算牌、压上家防下家、保炸弹时机、残局精确制导
 */
data class AiContext(
    val seat: Int,
    val hand: List<Card>,
    val lastMove: Move?,
    val lastMoveSeat: Int,
    val landlord: Int,
    /** 各座位剩牌数 */
    val handCounts: Map<Int, Int>,
    /** 全场已出的牌（困难模式记牌用） */
    val playedCards: List<Card>
)

class AiPlayer(
    val seat: Int,
    val level: AiLevel,
    private val seed: Long? = null
) {
    private val rng = seed?.let { Random(it) } ?: Random.Default

    // ------------------------------------------------ 叫抢决策

    /** 是否叫地主 */
    fun shouldCall(ctx: AiContext): Boolean {
        val strength = GameEngine.handStrength(ctx.hand)
        return when (level) {
            AiLevel.EASY -> strength >= 10 && rng.nextFloat() < 0.5f
            AiLevel.MEDIUM -> strength >= 12
            AiLevel.HARD -> strength >= 14 || (strength >= 10 && rng.nextFloat() < 0.4f)
        }
    }

    /** 是否抢地主 */
    fun shouldRob(ctx: AiContext): Boolean {
        val strength = GameEngine.handStrength(ctx.hand)
        return when (level) {
            AiLevel.EASY -> strength >= 14 && rng.nextFloat() < 0.4f
            AiLevel.MEDIUM -> strength >= 16
            AiLevel.HARD -> strength >= 18 || (strength >= 14 && hasBombOrRocket(ctx.hand))
        }
    }

    private fun hasBombOrRocket(hand: List<Card>): Boolean {
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
        if (counts.values.any { it == 4 }) return true
        return hand.any { it.rank == 16 } && hand.any { it.rank == 17 }
    }

    // ------------------------------------------------ 出牌决策

    /**
     * 选择出牌；返回 null 表示过牌（要不起 / 战略放弃）。
     * [ctx.landlord] 为地主座位。
     */
    fun chooseMove(ctx: AiContext): List<Card>? {
        val hand = ctx.hand
        if (hand.isEmpty()) return null

        // 一手能出完 → 直接赢
        val finishNow = if (ctx.lastMove == null) {
            MoveGen.genLeads(hand).firstOrNull { it.cards.size == hand.size }
        } else {
            MoveGen.genBeats(hand, ctx.lastMove).firstOrNull { it.cards.size == hand.size }
        }
        if (finishNow != null) return finishNow.cards

        return when (level) {
            AiLevel.EASY -> easyMove(ctx)
            AiLevel.MEDIUM -> mediumMove(ctx)
            AiLevel.HARD -> hardMove(ctx)
        }
    }

    // ------------------------------------------------ 简单

    private fun easyMove(ctx: AiContext): List<Card>? {
        val hand = ctx.hand
        if (ctx.lastMove == null) {
            // 领出：随机挑个非炸弹组合
            val leads = MoveGen.genLeads(hand).filter {
                it.type != MoveType.BOMB && it.type != MoveType.ROCKET
            }
            if (leads.isEmpty()) return hand.take(1)
            return leads[rng.nextInt(leads.size)].cards
        }
        val beats = MoveGen.genBeats(hand, ctx.lastMove)
        if (beats.isEmpty()) return null
        // 一半概率放过；否则在前三便宜选项里随机
        if (rng.nextFloat() < 0.45f) return null
        val top = beats.take(3).filter {
            it.type != MoveType.BOMB && it.type != MoveType.ROCKET
        }.ifEmpty { return if (rng.nextFloat() < 0.2f) beats.first().cards else null }
        return top[rng.nextInt(top.size)].cards
    }

    // ------------------------------------------------ 中等

    private fun mediumMove(ctx: AiContext): List<Card>? {
        val hand = ctx.hand
        if (ctx.lastMove == null) {
            return mediumLead(ctx)
        }
        val beats = MoveGen.genBeats(hand, ctx.lastMove)
        if (beats.isEmpty()) return null

        val cheap = beats.firstOrNull {
            it.type != MoveType.BOMB && it.type != MoveType.ROCKET
        }
        // 残局：剩牌少就用力压
        if (hand.size <= 4) {
            return beats.first().cards
        }
        // 队友优势牌不压
        if (isTeammate(ctx.lastMoveSeat, ctx) && ctx.lastMove!!.mainRank >= 13) {
            return null
        }
        // 压牌需要动 2/王 且是队友的小牌 → 过
        if (cheap != null && cheap.mainRank >= 15 && ctx.lastMove!!.mainRank <= 8 &&
            isTeammate(ctx.lastMoveSeat, ctx)
        ) {
            return null
        }
        // 只剩炸弹能压：中期舍不得，后期（剩牌≤8或对手剩牌≤5）才放
        if (cheap == null) {
            val bomb = beats.first()
            val opponentLow = opponentMinHand(ctx) <= 5
            if (hand.size <= 8 || opponentLow) return bomb.cards
            return null
        }
        return cheap.cards
    }

    private fun mediumLead(ctx: AiContext): List<Card>? {
        val hand = ctx.hand
        val leads = MoveGen.genLeads(hand).filter {
            it.type != MoveType.BOMB && it.type != MoveType.ROCKET
        }
        if (leads.isEmpty()) {
            // 只剩炸弹 → 炸
            return MoveGen.genLeads(hand).firstOrNull()?.cards ?: hand.take(1)
        }
        // 组合数 ≤ 2 时先出小的送终
        val combos = HandDecompose.decompose(hand)
        if (combos.size <= 2) {
            return leads.first().cards
        }
        // 对手（地主视角的农民 / 农民视角的地主）剩 1 张 → 不送单
        val opp1 = opponentLowSeat(ctx, threshold = 1)
        if (opp1 != null) {
            val nonSingle = leads.firstOrNull { it.type != MoveType.SINGLE }
            if (nonSingle != null) return nonSingle.cards
        }
        return leads.first().cards
    }

    // ------------------------------------------------ 困难

    private fun hardMove(ctx: AiContext): List<Card>? {
        val hand = ctx.hand
        val unseen = unseenCounts(ctx)

        if (ctx.lastMove == null) {
            return hardLead(ctx, unseen)
        }

        val beats = MoveGen.genBeats(hand, ctx.lastMove)
        if (beats.isEmpty()) return null

        val last = ctx.lastMove!!
        val lastByTeammate = isTeammate(ctx.lastMoveSeat, ctx)

        // 队友强势牌 → 让牌
        if (lastByTeammate && last.type == MoveType.BOMB) return null
        if (lastByTeammate && last.mainRank >= 14 && hand.size > 6) return null

        // 对手剩牌告急 → 全力压制
        val opponentLow = opponentLowSeat(ctx, threshold = 2)
        if (opponentLow != null && !lastByTeammate) {
            val strongest = beats.last()
            return strongest.cards
        }

        val cheap = beats.firstOrNull { it.type != MoveType.BOMB && it.type != MoveType.ROCKET }

        // 算牌：压完这手对手是否还能反压？
        if (cheap != null) {
            val canBeRebeaten = existsBeatInUnseen(cheap, unseen, exclude = ctx.seat)
            if (!canBeRebeaten && hand.size <= 10) {
                return cheap.cards  // 无人能压，放心走
            }
        }

        // 带牌动 2 / 王太早 → 忍
        if (cheap != null && cheap.mainRank >= 15 && hand.size > 10 &&
            last.mainRank <= 10 && !opponentLowWarn(ctx)
        ) {
            return null
        }

        if (cheap != null) return cheap.cards

        // 只剩炸弹 / 王炸
        val bomb = beats.first()
        val strategic = hand.size <= 9 || opponentMinHand(ctx) <= 6 ||
                (isLandlordMe(ctx) && farmerMinHand(ctx) <= 4)
        return if (strategic) bomb.cards else null
    }

    private fun hardLead(ctx: AiContext, unseen: Map<Int, Int>): List<Card>? {
        val hand = ctx.hand
        val leads = MoveGen.genLeads(hand).filter {
            it.type != MoveType.BOMB && it.type != MoveType.ROCKET
        }
        val allLeads = MoveGen.genLeads(hand)
        if (allLeads.isEmpty()) return hand.take(1)

        // 残局送终：组合 ≤ 2，先小后大
        val combos = HandDecompose.decompose(hand)
        if (combos.size <= 2) return leads.firstOrNull()?.cards ?: allLeads.first().cards

        // 队友只剩 1~2 张 → 送小单喂牌
        val mate = teammateSeat(ctx)
        if (mate != null && (ctx.handCounts[mate] ?: 20) <= 2) {
            val smallSingle = leads.firstOrNull { it.type == MoveType.SINGLE }
            if (smallSingle != null && smallSingle.mainRank <= 12) return smallSingle.cards
        }

        // 对手剩 1 张 → 不送单
        val opp1 = opponentLowSeat(ctx, threshold = 1)
        if (opp1 != null) {
            val nonSingle = leads.firstOrNull { it.type != MoveType.SINGLE }
            if (nonSingle != null) return nonSingle.cards
        }

        // 剩 2 张的地主/对手 → 出大牌压制型领出（顶张）
        if (opponentLowWarn(ctx)) {
            val strong = leads.lastOrNull { it.type != MoveType.SINGLE }
            if (strong != null && leads.first().mainRank < 10) return strong.cards
        }

        return leads.firstOrNull()?.cards ?: allLeads.first().cards
    }

    // ------------------------------------------------ 记牌（困难）

    /** 视角外未见牌：54 - 已出 - 自己手 */
    private fun unseenCounts(ctx: AiContext): Map<Int, Int> {
        val played = ctx.playedCards.groupBy { it.rank }.mapValues { it.value.size }
        val mine = ctx.hand.groupBy { it.rank }.mapValues { it.value.size }
        return (3..17).associateWith { r ->
            val total = if (r >= 16) 1 else 4
            total - (played[r] ?: 0) - (mine[r] ?: 0)
        }
    }

    /** 在未见牌中是否存在能压过 candidate 的牌（简单保守估计） */
    private fun existsBeatInUnseen(candidate: Move, unseen: Map<Int, Int>, exclude: Int): Boolean {
        return when (candidate.type) {
            MoveType.SINGLE -> (candidate.mainRank + 1..17).any { (unseen[it] ?: 0) > 0 }
            MoveType.PAIR -> (candidate.mainRank + 1..15).any { (unseen[it] ?: 0) >= 2 }
            MoveType.ROCKET -> false
            else -> (candidate.mainRank + 1..15).any { (unseen[it] ?: 0) >= 3 } ||
                    unseen.entries.any { it.value >= 4 }  // 大意估计：可能有炸
        }
    }

    // ------------------------------------------------ 身份工具

    private fun isTeammate(seat: Int, ctx: AiContext): Boolean {
        if (ctx.landlord < 0) return false
        return seat != ctx.landlord && ctx.seat != ctx.landlord && seat != ctx.seat
    }

    private fun isLandlordMe(ctx: AiContext): Boolean = ctx.seat == ctx.landlord

    private fun teammateSeat(ctx: AiContext): Int? {
        if (ctx.landlord < 0 || isLandlordMe(ctx)) return null
        return (0..2).firstOrNull { it != ctx.seat && it != ctx.landlord }
    }

    private fun opponentMinHand(ctx: AiContext): Int {
        val opponents = (0..2).filter { it != ctx.seat && isOpponent(it, ctx) }
        return opponents.minOf { ctx.handCounts[it] ?: 20 }
    }

    private fun farmerMinHand(ctx: AiContext): Int {
        if (!isLandlordMe(ctx)) return 99
        return (0..2).filter { it != ctx.seat }.minOf { ctx.handCounts[it] ?: 20 }
    }

    private fun isOpponent(seat: Int, ctx: AiContext): Boolean {
        if (ctx.landlord < 0) return true
        return (seat == ctx.landlord) != (ctx.seat == ctx.landlord)
    }

    /** 对手（非队友）中是否有剩牌 ≤ threshold 的座位 */
    private fun opponentLowSeat(ctx: AiContext, threshold: Int): Int? {
        return (0..2).firstOrNull { it != ctx.seat && isOpponent(it, ctx) &&
                (ctx.handCounts[it] ?: 20) <= threshold }
    }

    private fun opponentLowWarn(ctx: AiContext): Boolean = opponentLowSeat(ctx, 2) != null
}
