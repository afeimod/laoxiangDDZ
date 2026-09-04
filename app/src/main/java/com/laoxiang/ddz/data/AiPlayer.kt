package com.laoxiang.ddz.data

import kotlin.random.Random

/**
 * 斗地主 AI（三档难度，v13 大幅强化）
 *
 * EASY   —— 随机散漫：叫牌随缘、出牌乱走、常放过
 * MEDIUM —— 稳健实用：最小代价压牌且不拆结构、农民会让牌/顶牌、炸弹看时机
 * HARD   —— 老谋深算：记牌算牌、顶地主喂队友、防下家、炸弹精准压制、残局两手清
 *
 * 叫抢评估：王/2/炸弹/三张结构/手数加权，叫牌看"强度+手数"，抢牌需"王炸或双炸级"强牌。
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

    // ================================================================ 叫抢决策

    /** 是否叫地主 */
    fun shouldCall(ctx: AiContext): Boolean {
        val p = handPower(ctx.hand)
        return when (level) {
            AiLevel.EASY -> p >= 14 && rng.nextFloat() < 0.55f
            AiLevel.MEDIUM -> p >= 18 || (p >= 14 && rng.nextFloat() < 0.35f)
            AiLevel.HARD -> p >= 20 || (p >= 15 && rng.nextFloat() < 0.5f)
        }
    }

    /** 是否抢地主（要求比叫牌更强：王炸 / 多炸弹 / 强牌+少手数） */
    fun shouldRob(ctx: AiContext): Boolean {
        val p = handPower(ctx.hand)
        val bombN = bombCount(ctx.hand)
        return when (level) {
            AiLevel.EASY -> p >= 20 && rng.nextFloat() < 0.45f
            AiLevel.MEDIUM -> p >= 24 || (p >= 20 && bombN >= 1)
            AiLevel.HARD -> p >= 26 || (p >= 21 && bombN >= 1) ||
                    (p >= 18 && hasRocket(ctx.hand))
        }
    }

    /**
     * 手牌强度（0..~40）：叫抢与流局兜底共用。
     * 双王/单王、2、A、炸弹、三张与少手数加权。
     */
    fun handPower(hand: List<Card>): Int {
        val counts = hand.groupBy { it.rank }.mapValues { it.value.size }
        var p = 0
        val sj = counts[16] ?: 0
        val bj = counts[17] ?: 0
        if (sj > 0) p += 4
        if (bj > 0) p += 5
        if (sj > 0 && bj > 0) p += 4                     // 王炸额外
        counts.forEach { (r, c) ->
            when {
                c == 4 -> p += 7                          // 炸弹
                c == 3 -> p += if (r >= 13) 3 else 2      // 三张
            }
            if (r == 15) p += 3 * c                       // 2
            if (r == 14) p += c                           // A
        }
        // 手数惩罚：手数越少越强（最低分解）
        val hands = HandDecompose.decompose(hand).size
        p += (6 - hands).coerceAtLeast(-4) * 2
        return p
    }

    private fun bombCount(hand: List<Card>): Int =
        hand.groupBy { it.rank }.count { it.value.size == 4 }

    private fun hasRocket(hand: List<Card>): Boolean =
        hand.any { it.rank == 16 } && hand.any { it.rank == 17 }

    // ================================================================ 出牌决策

    /**
     * 选择出牌；返回 null 表示过牌（要不起 / 战略放弃）。
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
            val leads = MoveGen.genLeads(hand).filter { !it.type.isBombLike }
            if (leads.isEmpty()) return hand.take(1)
            return leads[rng.nextInt(leads.size)].cards
        }
        val beats = MoveGen.genBeats(hand, ctx.lastMove)
        if (beats.isEmpty()) return null
        if (rng.nextFloat() < 0.45f) return null
        val top = beats.take(3).filter { !it.type.isBombLike }
            .ifEmpty { return if (rng.nextFloat() < 0.2f) beats.first().cards else null }
        return top[rng.nextInt(top.size)].cards
    }

    // ------------------------------------------------ 中等

    private fun mediumMove(ctx: AiContext): List<Card>? {
        val hand = ctx.hand
        if (ctx.lastMove == null) return mediumLead(ctx)

        val beats = MoveGen.genBeats(hand, ctx.lastMove)
        if (beats.isEmpty()) return null
        val last = ctx.lastMove!!
        val lastByLandlord = ctx.lastMoveSeat == ctx.landlord
        val lastByTeammate = isTeammate(ctx.lastMoveSeat, ctx)

        // 只剩炸弹能压：中期忍，残局/对手告急才炸
        val nonBomb = beats.filter { !it.type.isBombLike }
        if (nonBomb.isEmpty()) {
            val bomb = beats.first()
            val press = lastByLandlord && (ctx.handCounts[ctx.landlord] ?: 20) <= 5
            return if (hand.size <= 8 || press) bomb.cards else null
        }

        // 队友的强势牌（≥A 或队友快走完）→ 让
        if (lastByTeammate) {
            val mateLow = (ctx.handCounts[ctx.lastMoveSeat] ?: 20) <= 4
            if (mateLow || last.mainRank >= 13) return null
            // 需要动 2/王去压队友的小牌 → 不值
            if (nonBomb.first().mainRank >= 15 && last.mainRank <= 10) return null
        }

        // 顶地主：地主出小单/小对且地主快走完 → 用能压的最小"大牌"顶
        if (lastByLandlord) {
            val lordLeft = ctx.handCounts[ctx.landlord] ?: 20
            if (lordLeft <= 8 && last.mainRank <= 10) {
                val top = nonBomb.filter { it.mainRank >= 14 }.minByOrNull { it.mainRank }
                if (top != null) return top.cards
            }
        }

        // 残局：剩牌 ≤ 4 全力走
        if (hand.size <= 4) return nonBomb.first().cards

        // 常规：最便宜且不拆结构的
        return nonBomb.minWithOrNull(
            compareBy({ splitPenalty(it, hand) }, { it.mainRank * 2 - it.cards.size })
        )?.cards
    }

    private fun mediumLead(ctx: AiContext): List<Card>? {
        val hand = ctx.hand
        val leads = MoveGen.genLeads(hand)
        val safeLeads = leads.filter { !it.type.isBombLike }
        if (safeLeads.isEmpty()) return leads.firstOrNull()?.cards ?: hand.take(1)

        val combos = HandDecompose.decompose(hand)
        // 两手清：先出对手压不住的那手，压不住就走完
        if (combos.size <= 2) return leads.first().cards

        // 对手剩 1 张 → 不送单
        if (opponentLowSeat(ctx, 1) != null) {
            safeLeads.firstOrNull { it.type != MoveType.SINGLE }?.let { return it.cards }
        }
        return safeLeads.first().cards
    }

    // ------------------------------------------------ 困难

    private fun hardMove(ctx: AiContext): List<Card>? {
        val hand = ctx.hand
        val unseen = unseenCounts(ctx)

        if (ctx.lastMove == null) return hardLead(ctx, unseen)

        val beats = MoveGen.genBeats(hand, ctx.lastMove)
        if (beats.isEmpty()) return null
        val last = ctx.lastMove!!
        val nonBomb = beats.filter { !it.type.isBombLike }
        val lastByLandlord = ctx.lastMoveSeat == ctx.landlord
        val lastByTeammate = isTeammate(ctx.lastMoveSeat, ctx)

        // ---- 生死局判断：出牌方再走一手就赢 → 拼命压（可用炸弹）
        val playerAboutToWin = (ctx.handCounts[ctx.lastMoveSeat] ?: 20) <= 2
        if (playerAboutToWin && lastByLandlord) {
            return beats.last().cards           // 最强的压（含炸弹）
        }
        if (playerAboutToWin && lastByTeammate) {
            return null                          // 队友要走 → 让他走
        }

        // ---- 队友的牌：强势让过 / 喂牌
        if (lastByTeammate) {
            val mateLeft = ctx.handCounts[ctx.lastMoveSeat] ?: 20
            if (mateLeft <= 5 || last.mainRank >= 13) return null
            if (last.type == MoveType.BOMB) return null
        }

        // ---- 非炸弹路线
        if (nonBomb.isNotEmpty()) {
            val lordLeft = ctx.handCounts[ctx.landlord] ?: 20
            val cheap = nonBomb.minWithOrNull(
                compareBy({ splitPenalty(it, hand) }, { it.mainRank * 2 - it.cards.size })
            )!!

            // 顶地主：地主小牌且手数紧 → 顶到 A/2/王（选能压的最小大牌）
            if (lastByLandlord && lordLeft <= 10 && last.mainRank <= 11) {
                val top = nonBomb.filter { it.mainRank >= 14 }.minByOrNull { it.mainRank }
                if (top != null) return top.cards
            }

            // 记牌：压完这手无人能反压 → 放心走（省大牌）
            val safe = !existsBeatInUnseen(cheap, unseen)
            if (safe && hand.size <= 12) return cheap.cards

            // 大牌价值管理：中局别拿 2/王 压太小的牌（地主不紧时）
            val earlyWaste = cheap.mainRank >= 15 && hand.size > 10 &&
                    last.mainRank <= 9 && lordLeft > 6
            if (!earlyWaste) return cheap.cards
        }

        // ---- 只剩/需要炸弹
        val bomb = beats.first()
        val lordLeft = ctx.handCounts[ctx.landlord] ?: 20
        val strategic = hand.size <= 9 ||
                (lastByLandlord && lordLeft <= 6) ||
                farmerMinHand(ctx) <= 4
        return if (strategic) bomb.cards else null
    }

    private fun hardLead(ctx: AiContext, unseen: Map<Int, Int>): List<Card>? {
        val hand = ctx.hand
        val leads = MoveGen.genLeads(hand)
        val safeLeads = leads.filter { !it.type.isBombLike }
        if (safeLeads.isEmpty()) return leads.firstOrNull()?.cards ?: hand.take(1)

        val combos = HandDecompose.decompose(hand)

        // ---- 残局两手清：优先出"没人压得住"的一手；都压得住 → 先出大的逼牌
        if (combos.size == 2) {
            val unbeat = combos.map { it.move }.filter { !existsBeatInUnseen(it, unseen) }
            if (unbeat.isNotEmpty()) return unbeat.minByOrNull { it.mainRank }!!.cards
            return combos.maxByOrNull { it.move.mainRank }!!.move.cards
        }
        if (combos.size == 1) return leads.first().cards

        // ---- 队友只剩 1~2 张 → 喂小单
        val mate = teammateSeat(ctx)
        if (mate != null && (ctx.handCounts[mate] ?: 20) <= 2) {
            val small = safeLeads.firstOrNull {
                it.type == MoveType.SINGLE && it.mainRank <= 12
            }
            if (small != null) return small.cards
        }

        // ---- 对手剩 1 张 → 绝不送单
        if (opponentLowSeat(ctx, 1) != null) {
            val nonSingle = safeLeads.firstOrNull { it.type != MoveType.SINGLE }
            if (nonSingle != null) return nonSingle.cards
        }

        // ---- 对手剩 2 张 → 出牌型大的压制型领出
        if (opponentLowSeat(ctx, 2) != null) {
            val strong = safeLeads.lastOrNull { it.type != MoveType.SINGLE }
            if (strong != null && safeLeads.first().mainRank < 12) return strong.cards
        }

        // ---- 记牌：选对手大概率压不住的小招先走
        val likelySafe = safeLeads.firstOrNull { !existsBeatInUnseen(it, unseen) }
        if (likelySafe != null && likelySafe.cards.size >= 4) return likelySafe.cards

        return safeLeads.first().cards
    }

    // ================================================================ 通用工具

    /**
     * 拆结构惩罚：这手牌用掉的牌会拆散手牌里的炸弹/三张/对子结构则加罚。
     * （炸弹在 genBeats 已有 +200，此处再防"拆对/拆三凑单张"）
     */
    private fun splitPenalty(move: Move, hand: List<Card>): Int {
        if (move.type.isBombLike) return 0
        val handCounts = hand.groupBy { it.rank }.mapValues { it.value.size }
        val usedCounts = move.cards.groupBy { it.rank }.mapValues { it.value.size }
        var penalty = 0
        usedCounts.forEach { (r, used) ->
            val avail = handCounts[r] ?: 0
            val left = avail - used
            if (left > 0) {
                penalty += when (left) {
                    1 -> 2
                    2 -> 4
                    3 -> 8
                    else -> 12
                }
            }
        }
        return penalty
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

    /** 未见牌中是否存在能压过 candidate 的牌 */
    private fun existsBeatInUnseen(candidate: Move, unseen: Map<Int, Int>): Boolean {
        return when (candidate.type) {
            MoveType.SINGLE -> (candidate.mainRank + 1..17).any { (unseen[it] ?: 0) > 0 }
            MoveType.PAIR -> (candidate.mainRank + 1..15).any { (unseen[it] ?: 0) >= 2 }
            MoveType.ROCKET -> false
            else -> (candidate.mainRank + 1..15).any { (unseen[it] ?: 0) >= 3 } ||
                    unseen.entries.any { it.value >= 4 }
        }
    }

    // ------------------------------------------------ 身份工具

    private fun isTeammate(seat: Int, ctx: AiContext): Boolean {
        if (ctx.landlord < 0) return false
        return seat != ctx.landlord && ctx.seat != ctx.landlord && seat != ctx.seat
    }

    private fun teammateSeat(ctx: AiContext): Int? {
        if (ctx.landlord < 0 || ctx.seat == ctx.landlord) return null
        return (0..2).firstOrNull { it != ctx.seat && it != ctx.landlord }
    }

    private fun farmerMinHand(ctx: AiContext): Int {
        if (ctx.seat != ctx.landlord) return 99
        return (0..2).filter { it != ctx.seat }.minOf { ctx.handCounts[it] ?: 20 }
    }

    private fun opponentLowSeat(ctx: AiContext, threshold: Int): Int? {
        return (0..2).firstOrNull {
            it != ctx.seat && isOpponent(it, ctx) && (ctx.handCounts[it] ?: 20) <= threshold
        }
    }

    private fun isOpponent(seat: Int, ctx: AiContext): Boolean {
        if (ctx.landlord < 0) return true
        return (seat == ctx.landlord) != (ctx.seat == ctx.landlord)
    }
}
