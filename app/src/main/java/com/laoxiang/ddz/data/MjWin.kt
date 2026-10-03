package com.laoxiang.ddz.data

/**
 * 麻将核心规则（胡牌判定 / 算番 / 向听数）—— 纯 Kotlin，无 Android 依赖
 *
 * counts 为 0..33 的连续编码计数（万0-8 筒9-17 条18-26 字27-33）。
 * 癞子（红中）不入 counts，单独以 jokers 数量参与判定（可当任意牌）。
 *
 * 胡牌判定采用「首张必消」回溯：
 *  - 每层取最小非零 code，枚举该牌所在的刻子/顺子（癞子可补位）；
 *  - 对子先拆（含双癞子对）；剩余癞子必须恰好凑成整刻子。
 *
 * 副露模型 MjMeld / MjMeldType 定义在 MjEngine.kt（本文件与其同包共用）。
 */
object MjRules {

    // ------------------------------------------------ 计数工具

    fun countsOf(tiles: List<MjTile>): IntArray {
        val c = IntArray(34)
        tiles.forEach { c[it.code]++ }
        return c
    }

    /** 幺九字 13 种 code（十三幺用） */
    private val ORPHANS = intArrayOf(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33)

    // ------------------------------------------------ 胡牌判定

    enum class MjWinShape { STANDARD, SEVEN_PAIRS, THIRTEEN_ORPHANS }

    data class MjWinInfo(val shape: MjWinShape, val luxurious: Boolean)

    /**
     * 是否成和。
     * @param counts 手牌计数（癞子已剔除）
     * @param jokers 癞子数量
     * @param allowSevenPairs 允许七对
     * @param allowThirteen 允许十三幺
     * @param requireTwoSuits 川麻：数牌必须 ≤2 门（缺一门）
     */
    fun canWin(
        counts: IntArray,
        jokers: Int,
        allowSevenPairs: Boolean = true,
        allowThirteen: Boolean = true,
        requireTwoSuits: Boolean = false
    ): MjWinInfo? {
        val total = counts.sum() + jokers
        if (total < 2 || (total - 2) % 3 != 0) return null
        if (requireTwoSuits) {
            var suits = 0
            for (s in 0 until 3) {
                var any = false
                for (i in 0 until 9) if (counts[s * 9 + i] > 0) { any = true; break }
                if (any) suits++
            }
            if (suits > 2) return null
        }
        // 十三幺
        if (allowThirteen) {
            var missing = 0
            var realPair = false
            for (c in ORPHANS) {
                if (counts[c] == 0) missing++ else if (counts[c] >= 2) realPair = true
            }
            if (missing <= jokers && (jokers > missing || realPair)) {
                return MjWinInfo(MjWinShape.THIRTEEN_ORPHANS, luxurious = false)
            }
        }
        // 七对（豪华 = 有真四张）
        if (allowSevenPairs) {
            var odd = 0
            var pairs = 0
            var quad = false
            for (c in 0..33) {
                pairs += counts[c] / 2
                odd += counts[c] % 2
                if (counts[c] >= 4) quad = true
            }
            if (odd <= jokers && (jokers - odd) % 2 == 0 &&
                pairs + (jokers + odd) / 2 == total / 2
            ) {
                return MjWinInfo(MjWinShape.SEVEN_PAIRS, luxurious = quad)
            }
        }
        // 标准 4 面子 + 1 对
        val melds = (total - 2) / 3
        val work = counts.copyOf()
        // 真牌对
        for (c in 0..33) if (work[c] >= 2) {
            work[c] -= 2
            if (split(work, jokers, melds, allowChi = true)) {
                work[c] += 2
                return MjWinInfo(MjWinShape.STANDARD, luxurious = false)
            }
            work[c] += 2
        }
        // 双癞子作对
        if (jokers >= 2 && split(work, jokers - 2, melds, allowChi = true)) {
            return MjWinInfo(MjWinShape.STANDARD, luxurious = false)
        }
        return null
    }

    /** 首张必消回溯：把 counts 完整拆成 [melds] 个面子（癞子可补位） */
    private fun split(counts: IntArray, jokers: Int, melds: Int, allowChi: Boolean): Boolean {
        if (melds < 0) return false
        var i = 0
        while (i < 34 && counts[i] == 0) i++
        if (i == 34) return jokers == melds * 3
        // 刻子：枚举用几张真牌（3/2/1/0 张真牌 + 癞子补足）
        val maxReal = minOf(3, counts[i])
        for (real in maxReal downTo 0) {
            val needJ = 3 - real
            if (needJ > jokers) continue
            counts[i] -= real
            if (split(counts, jokers - needJ, melds - 1, allowChi)) {
                counts[i] += real
                return true
            }
            counts[i] += real
        }
        if (!allowChi || i >= 27) return false
        val pos = i % 9
        // 顺子 i,i+1,i+2 —— i 为首张（真牌必用），缺位用癞子
        if (pos <= 6) {
            val o1 = counts[i + 1] > 0
            val o2 = counts[i + 2] > 0
            for (j1 in 0..1) {
                if (j1 == 0 && !o1) continue
                if (j1 == 1 && jokers < 1) continue
                for (j2 in 0..1) {
                    if (j2 == 0 && !o2) continue
                    if (j2 == 1 && jokers < 1 + j2) continue
                    counts[i]--
                    if (j1 == 0) counts[i + 1]--
                    if (j2 == 0) counts[i + 2]--
                    val usedJ = j1 + j2
                    val ok = split(counts, jokers - usedJ, melds - 1, allowChi)
                    if (j1 == 0) counts[i + 1]++
                    if (j2 == 0) counts[i + 2]++
                    counts[i]++
                    if (ok) return true
                }
            }
        }
        // 顺子 i 为中张：i-1 癞子 + i + i+1（真或癞）
        if (pos >= 1 && pos <= 7 && jokers >= 1) {
            val o2 = counts[i + 1] > 0
            for (j2 in 0..1) {
                if (j2 == 0 && !o2) continue
                if (j2 == 1 && jokers < 2) continue
                counts[i]--
                if (j2 == 0) counts[i + 1]--
                val ok = split(counts, jokers - 1 - j2, melds - 1, allowChi)
                if (j2 == 0) counts[i + 1]++
                counts[i]++
                if (ok) return true
            }
        }
        // 顺子 i 为尾张：i-2、i-1 双癞子 + i
        if (pos >= 2 && jokers >= 2) {
            counts[i]--
            val ok = split(counts, jokers - 2, melds - 1, allowChi)
            counts[i]++
            if (ok) return true
        }
        return false
    }

    /** 仅刻子拆分（碰碰胡/对对胡判定，癞子可补刻） */
    private fun splitPungOnly(counts: IntArray, jokers: Int, melds: Int): Boolean {
        if (melds < 0) return false
        var i = 0
        while (i < 34 && counts[i] == 0) i++
        if (i == 34) return jokers == melds * 3
        val maxReal = minOf(3, counts[i])
        for (real in maxReal downTo 0) {
            val needJ = 3 - real
            if (needJ > jokers) continue
            counts[i] -= real
            if (splitPungOnly(counts, jokers - needJ, melds - 1)) {
                counts[i] += real
                return true
            }
            counts[i] += real
        }
        return false
    }

    /** 碰碰胡（对对胡）形态：面子全为刻/杠 */
    fun isAllPungs(counts: IntArray, jokers: Int, melds: List<MjMeld>): Boolean {
        if (melds.any { it.type == MjMeldType.CHI }) return false
        val total = counts.sum() + jokers
        if (total < 2 || (total - 2) % 3 != 0) return false
        val need = (total - 2) / 3
        val work = counts.copyOf()
        for (c in 0..33) if (work[c] >= 2) {
            work[c] -= 2
            if (splitPungOnly(work, jokers, need)) {
                work[c] += 2
                return true
            }
            work[c] += 2
        }
        return jokers >= 2 && splitPungOnly(work, jokers - 2, need)
    }

    // ------------------------------------------------ 听牌（进张）

    /**
     * 听牌张列表：往 [counts] 里加一张（癞子不动）能成和的 code。
     * @param onlyNumber 川麻场景：只考虑 0..26
     */
    fun tingCodes(
        counts: IntArray,
        jokers: Int = 0,
        allowSevenPairs: Boolean = true,
        allowThirteen: Boolean = true,
        requireTwoSuits: Boolean = false,
        onlyNumber: Boolean = false
    ): List<Int> {
        val res = ArrayList<Int>()
        val work = counts.copyOf()
        val range = if (onlyNumber) 0..26 else 0..33
        for (c in range) {
            if (work[c] >= 4) continue
            work[c]++
            if (canWin(work, jokers, allowSevenPairs, allowThirteen, requireTwoSuits) != null) res += c
            work[c]--
        }
        return res
    }

    // ------------------------------------------------ 向听数（AI 用）

    /**
     * 标准牌型向听估计（0=听牌，-1=已和；含七对取较优）。
     * @param jokers 癞子按「每 3 张抵 1 面子、余 1-2 张当对子」折算
     * @param meldsMade 已副露面子数（碰/杠/吃）
     */
    fun shanten(counts: IntArray, jokers: Int = 0, meldsMade: Int = 0): Int {
        val work = counts.copyOf()
        val melds = jokers / 3 + meldsMade
        val pairCredit = jokers % 3 >= 1
        val st = dfsShanten(work, 0, melds, 0, pairCredit)
        // 七对（有副露时无意义）
        if (meldsMade == 0) {
            var pairs = 0
            var odd = 0
            var kinds = 0
            for (c in 0..33) {
                pairs += work[c] / 2
                odd += work[c] % 2
                if (work[c] > 0) kinds++
            }
            // 癞子：先配单张，剩余互相配对
            val jUsed = minOf(odd, jokers)
            val p7 = pairs + jUsed + (jokers - jUsed) / 2
            val s7 = 6 - p7 + maxOf(0, 7 - kinds - (if (jokers > 0) 1 else 0))
            return minOf(st, s7).coerceAtLeast(-1)
        }
        return st
    }

    /** maximize B = 2*melds + min(d, 4-melds) + hasPair → shanten = 8 - B（13 张下限 0 由调用处约束） */
    private fun dfsShanten(
        counts: IntArray, start: Int, melds: Int, parts: Int, hasPair: Boolean
    ): Int {
        var best = 8 - 2 * melds - minOf(parts, 4 - melds) - (if (hasPair) 1 else 0)
        var i = start
        while (i < 34) {
            if (counts[i] > 0) break
            i++
        }
        if (i == 34) return best
        if (melds + parts >= 5) {
            // 块已满：剩余牌全部当浮牌跳过
            return best
        }
        // 刻子
        if (counts[i] >= 3 && melds < 4) {
            counts[i] -= 3
            best = minOf(best, dfsShanten(counts, i, melds + 1, parts, hasPair))
            counts[i] += 3
        }
        // 顺子
        if (i < 27 && i % 9 <= 6 && counts[i + 1] > 0 && counts[i + 2] > 0 && melds < 4) {
            counts[i]--; counts[i + 1]--; counts[i + 2]--
            best = minOf(best, dfsShanten(counts, i, melds + 1, parts, hasPair))
            counts[i]++; counts[i + 1]++; counts[i + 2]++
        }
        // 对子（部分）
        if (counts[i] >= 2) {
            counts[i] -= 2
            best = minOf(best, dfsShanten(counts, i, melds, parts + 1, true))
            counts[i] += 2
        }
        // 搭子（相邻/隔一张）
        if (i < 27) {
            val pos = i % 9
            if (pos <= 7 && counts[i + 1] > 0) {
                counts[i]--; counts[i + 1]--
                best = minOf(best, dfsShanten(counts, i, melds, parts + 1, hasPair))
                counts[i]++; counts[i + 1]++
            }
            if (pos <= 6 && counts[i + 2] > 0) {
                counts[i]--; counts[i + 2]--
                best = minOf(best, dfsShanten(counts, i, melds, parts + 1, hasPair))
                counts[i]++; counts[i + 2]++
            }
        }
        // 浮牌（跳过该张）
        counts[i]--
        best = minOf(best, dfsShanten(counts, i, melds, parts, hasPair))
        counts[i]++
        return best
    }

    // ------------------------------------------------ 算番

    data class MjFanResult(val fans: List<Pair<String, Int>>, val total: Int)

    /** 副露与手牌中某 code 的总张数 */
    private fun copies(counts: IntArray, melds: List<MjMeld>, code: Int): Int {
        var n = counts[code]
        melds.forEach { m -> if (m.code == code) n += m.tiles.size }
        return n
    }

    private fun suitSpread(counts: IntArray, melds: List<MjMeld>): IntArray {
        val s = IntArray(4)
        for (c in 0..33) if (counts[c] > 0) s[c / 9] += counts[c]
        melds.forEach { m -> s[m.code / 9] += m.tiles.size }
        return s
    }

    /**
     * 大众/癞子推倒胡计番：番种取最大 + 附加番累加。
     * 番种：平胡1 对对4 混一色4 清一色8 七对6 豪华七对16 字一色16
     *       大三元16 小三元8 清么九16 十三幺16 天胡16 地胡8
     * 附加：自摸+1 杠上开花+2 抢杠+2 海底捞月+2
     * 癞子局：总番 × 2^min(癞子数,3)
     */
    fun fanDazhong(
        counts: IntArray,
        jokers: Int,
        melds: List<MjMeld>,
        winTile: MjTile,
        selfDraw: Boolean,
        gangDraw: Boolean,
        robGang: Boolean,
        lastWall: Boolean,
        dealerFirst: Boolean,
        firstDraw: Boolean,
        laizi: Boolean
    ): MjFanResult {
        val info = canWin(counts, jokers)!!
        val spread = suitSpread(counts, melds)
        val numSuits = (0 until 3).count { spread[it] > 0 }
        val hasZi = spread[3] > 0
        val allMeldPung = melds.none { it.type == MjMeldType.CHI }

        val patterns = ArrayList<Pair<String, Int>>()
        patterns += "平胡" to 1
        if (info.shape == MjWinShape.THIRTEEN_ORPHANS) patterns += "十三幺" to 16
        if (info.shape == MjWinShape.SEVEN_PAIRS) {
            patterns += if (info.luxurious) "豪华七对" to 16 else "七对" to 6
        }
        if (info.shape == MjWinShape.STANDARD) {
            if (isAllPungs(counts, jokers, melds)) patterns += "对对胡" to 4
            if (numSuits == 1 && hasZi) patterns += "混一色" to 4
            if (numSuits == 1 && !hasZi) patterns += "清一色" to 8
            if (numSuits == 0) patterns += "字一色" to 16
            // 清么九：全部为幺九数牌
            var allYJ = true
            for (c in 0..26) if (counts[c] > 0 && !(c % 9 == 0 || c % 9 == 8)) allYJ = false
            melds.forEach { m -> m.tiles.forEach { t -> if (!t.isYaoJiu) allYJ = false } }
            if (allYJ && !hasZi && counts.sum() > 0) patterns += "清么九" to 16
            // 大三元 / 小三元
            val zhong = copies(counts, melds, 31)
            val fa = copies(counts, melds, 32)
            val bai = copies(counts, melds, 33)
            if (zhong >= 3 && fa >= 3 && bai >= 3) patterns += "大三元" to 16
            else {
                val three = listOf(zhong, fa, bai).count { it >= 3 }
                val two = listOf(zhong, fa, bai).count { it == 2 }
                if (three >= 2 && two >= 1) patterns += "小三元" to 8
            }
        }
        if (dealerFirst) patterns += "天胡" to 16
        if (firstDraw && !dealerFirst) patterns += "地胡" to 8

        var pattern = patterns.maxOf { it.second }
        val bonuses = ArrayList<Pair<String, Int>>()
        if (selfDraw) bonuses += "自摸" to 1
        if (gangDraw) bonuses += "杠上开花" to 2
        if (robGang) bonuses += "抢杠胡" to 2
        if (lastWall) bonuses += "海底捞月" to 2
        val bonusSum = bonuses.sumOf { it.second }
        var total = pattern + bonusSum
        val all = ArrayList(patterns.filter { it.second == pattern })
        all += bonuses
        if (laizi && jokers > 0) {
            val mult = 1 shl minOf(jokers, 3)
            total *= mult
            all += "癞子×$mult" to 0
        }
        return MjFanResult(all, total)
    }

    /**
     * 四川血战计番（倍数相乘制）：
     * 平胡×1 对对×2 清一色×4 七对×4 龙七对×8 金钩钩×4 十八罗汉×8
     * 天胡×16 地胡×16 杠上花×2 根×2/根
     */
    fun fanSichuan(
        counts: IntArray,
        melds: List<MjMeld>,
        winTile: MjTile,
        gangDraw: Boolean,
        dealerFirst: Boolean,
        firstDraw: Boolean
    ): MjFanResult {
        val info = canWin(counts, 0, requireTwoSuits = true)!!
        val spread = suitSpread(counts, melds)
        val numSuits = (0 until 3).count { spread[it] > 0 }
        val gangs = melds.count { it.isGang }
        val fans = ArrayList<Pair<String, Int>>()
        if (info.shape == MjWinShape.SEVEN_PAIRS) {
            fans += if (info.luxurious) "龙七对" to 8 else "七对" to 4
            var mult = fans.sumOf { it.second }
            if (gangDraw) { fans += "杠上花" to 2; mult *= 2 }
            if (dealerFirst) { fans += "天胡" to 16; mult *= 16 }
            if (firstDraw && !dealerFirst) { fans += "地胡" to 16; mult *= 16 }
            return MjFanResult(fans, mult)
        }
        val allPung = melds.none { it.type == MjMeldType.CHI } &&
                isAllPungs(counts, 0, melds)
        var mult = 1
        if (allPung) {
            val concealed = counts.sum()
            if (concealed == 2 && melds.size == 4) fans += "金钩钩" to 4
            else fans += "对对胡" to 2
        }
        if (numSuits == 1) fans += "清一色" to 4
        if (gangs == 4) fans += "十八罗汉" to 8
        if (gangDraw) fans += "杠上花" to 2
        if (dealerFirst) fans += "天胡" to 16
        if (firstDraw && !dealerFirst) fans += "地胡" to 16
        mult = if (fans.isEmpty()) 1 else fans.sumOf { it.second }.coerceAtLeast(1)
        if (fans.isEmpty()) fans += "平胡" to 1
        // 根（手牌或副露中任意 4 张相同）
        var roots = 0
        for (c in 0..26) if (copies(counts, melds, c) >= 4) roots++
        if (roots > 0) {
            fans += "根×$roots" to 1
            repeat(roots) { mult *= 2 }
        }
        return MjFanResult(fans, mult)
    }
}
