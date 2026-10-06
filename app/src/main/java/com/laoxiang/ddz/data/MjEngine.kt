package com.laoxiang.ddz.data

import kotlin.random.Random

/**
 * 麻将引擎（纯 Kotlin，无 Android 依赖）—— 三种玩法共用一套状态机：
 *
 * 【大众麻将】136 张带字牌 · 吃碰杠胡 · 带番型推倒胡 · 点炮/自摸 · 一炮多响
 * 【癞子麻将】136 张 · 4 张红中当癞子（不可打出）· 癞子翻番 · 其余同大众
 * 【四川麻将】108 张万筒条 · 定缺 → 换三张 → 有缺必打 · 无吃 · 只许自摸
 *             · 刮风下雨即付 · 血战到底（打到三家胡/流局）· 流局查叫查花猪
 */
enum class MjMode(
    val label: String,
    val desc: String,
    val gameId: String,
    val withZi: Boolean,
    val selfDrawOnly: Boolean,
    val baseScore: Int
) {
    DAZHONG("大众麻将", "带番推倒胡 · 吃碰杠 · 一炮多响", "mjdazhong", true, false, 100),
    LAIZI("癞子麻将", "红中癞子 · 不可打癞子 · 癞子翻番", "mjlaizi", true, false, 100),
    SICHUAN("四川麻将", "血战到底 · 换三张 · 定缺 · 只自摸", "mjsichuan", false, true, 50);

    /** 癞子 code（红中 = 31）；非癞子局返回 -1 */
    val jokerCode: Int get() = if (this == LAIZI) 31 else -1
}

enum class MjPhase { WAITING, DINGQUE, SWAP3, PLAYING, GAME_OVER }

/** 副露类型 */
@kotlinx.serialization.Serializable
enum class MjMeldType(val label: String) {
    PENG("碰"), CHI("吃"), GANG_MING("明杠"), GANG_AN("暗杠"), GANG_BU("补杠")
}

@kotlinx.serialization.Serializable
data class MjMeld(
    val type: MjMeldType,
    val tiles: List<MjTile>,
    /** 供牌者座位（吃/碰/点杠）；暗杠 = -1 */
    val from: Int = -1
) {
    val code: Int get() = tiles.first().code
    val isGang: Boolean get() = type != MjMeldType.PENG && type != MjMeldType.CHI
}

/** 可宣告的操作（吃含中间张区分多种） */
@kotlinx.serialization.Serializable
data class MjClaimOpt(
    val kind: String,       // HU / PENG / GANG / CHI
    val chiMid: Int = -1,   // 吃：所吃中间张 code（-1 无）
    val label: String = ""
)

// ------------------------------------------------ 快照

@kotlinx.serialization.Serializable
data class MjSeatView(
    val seat: Int,
    val name: String,
    val avatar: Int,
    val isAi: Boolean,
    val isTurn: Boolean,
    val handCount: Int,
    val hand: List<MjTile> = emptyList(),
    val melds: List<MjMeld> = emptyList(),
    val river: List<MjTile> = emptyList(),
    val dingque: Int = -1,
    val huRank: Int = 0,
    val fanLabel: String = "",
    val ting: Boolean = false,
    val isDealer: Boolean = false
)

@kotlinx.serialization.Serializable
data class MjHuDetail(
    val seat: Int,
    val fans: List<String>,
    val total: Int,
    val selfDraw: Boolean,
    val winTile: MjTile? = null
)

@kotlinx.serialization.Serializable
data class MjResult(
    val scoreDelta: Map<Int, Int>,
    val huOrder: List<Int>,
    val liuju: Boolean,
    val details: List<MjHuDetail> = emptyList(),
    /** 刮风下雨累计（四川） */
    val gangDelta: Map<Int, Int> = emptyMap(),
    val note: String = ""
)

@kotlinx.serialization.Serializable
data class MjSnapshot(
    val mode: MjMode,
    val phase: MjPhase,
    val round: Int,
    val dealer: Int,
    val turn: Int,
    val awaitingDiscard: Boolean,
    val wallCount: Int,
    val seats: List<MjSeatView>,
    val lastDiscardSeat: Int = -1,
    val lastDiscardTile: MjTile? = null,
    val laiziCode: Int = -1,
    val myClaims: List<MjClaimOpt> = emptyList(),
    /** 换三张方向（四川）：1=下家 2=对家 3=上家 */
    val swapDir: Int = -1,
    /** 我是否已提交换三张 */
    val swapPicked: Boolean = false,
    /** 刮风下雨实时分（四川） */
    val gangDelta: Map<Int, Int> = emptyMap(),
    /** 视角玩家当前可自摸胡 */
    val myHu: Boolean = false,
    /** 视角玩家刚摸到的牌 id（待出牌时在第14墩单独展示；无摸牌/非待出牌为 -1） */
    val drawnTileId: Int = -1,
    /** 视角玩家可暗杠的 code */
    val anGangCodes: List<Int> = emptyList(),
    /** 视角玩家可补杠的牌 id */
    val buGangIds: List<Int> = emptyList(),
    /** 开局骰子点数（1..6；旧快照/未掷为 -1） */
    val dice1: Int = -1,
    val dice2: Int = -1,
    val result: MjResult? = null
)

// ------------------------------------------------ 事件

sealed class MjEvent {
    object Shuffle : MjEvent()
    data class DingQue(val seat: Int, val suit: Int) : MjEvent()
    data class SwapDone(val dir: Int) : MjEvent()
    data class Draw(val seat: Int) : MjEvent()
    data class Discard(val seat: Int, val tile: MjTile) : MjEvent()
    data class Chi(val seat: Int) : MjEvent()
    data class Peng(val seat: Int) : MjEvent()
    data class Gang(val seat: Int, val type: MjMeldType) : MjEvent()
    data class Hu(
        val seat: Int, val selfDraw: Boolean,
        val fans: List<String>, val total: Int, val winTile: MjTile?
    ) : MjEvent()
    object LiuJu : MjEvent()
    object GameOver : MjEvent()
}

// ------------------------------------------------ 引擎

class MjEngine(private val randomSeed: Long? = null) {

    // 每局重新播种（时间戳混合，避免任何跨局相关性）；测试固定 seed 时不变
    private var rng: Random = randomSeed?.let { Random(it) } ?: Random.Default

    val players = ArrayList<PlayerState>(4)
    var mode: MjMode = MjMode.DAZHONG
        private set
    var phase = MjPhase.WAITING
        private set
    var roundNumber = 0
        private set

    var dealer = 0
        private set

    /** 开局骰子点数（1..6；未掷为 -1）——同时用于切墙 */
    var dice1: Int = -1
        private set
    var dice2: Int = -1
        private set
    var turn = -1
        private set
    var awaitingDiscard = false
        private set

    /** 当前回合玩家刚摸到的牌 id（未出牌时有效；吃碰后/出牌后为 -1）—— 用于手牌第14墩单独展示 */
    var lastDrawnId: Int = -1
        private set

    val wallCount: Int get() = wall.size

    /** 红中癞子局的红中 code，其他 -1 */
    val jokerCode: Int get() = mode.jokerCode

    private val wall = ArrayDeque<MjTile>()
    val rivers = Array(4) { ArrayList<MjTile>() }
    val meldsOf = Array(4) { ArrayList<MjMeld>() }
    val dingque = IntArray(4) { -1 }
    private val swapPick = Array(4) { ArrayList<MjTile>() }
    var swapDir = -1
        private set

    /** 牌墙快照（测试/展示用） */
    fun wallTiles(): List<MjTile> = wall.toList()

    /** 当前宣告窗口 */
    private var claimCtxTile: MjTile? = null
    private var claimCtxSeat = -1
    /** 抢杠上下文：补杠者座位 + 补的 code */
    private var robGangSeat = -1
    private var robGangCode = -1
    private var robGangMeldIdx = -1
    private val claimOptions = HashMap<Int, List<MjClaimOpt>>()
    private val claimResponded = HashMap<Int, MjClaimOpt?>()

    /** 血战：已胡座位顺序 */
    val huOrder = ArrayList<Int>()
    private val fanLabels = HashMap<Int, String>()

    /** 刮风下雨累计分（四川） */
    val gangDelta = HashMap<Int, Int>()

    private var drawCount = IntArray(4)
    private var totalDiscards = 0
    private var lastDrawGang = false
    private var lastDrawSea = false

    var result: MjResult? = null
        private set

    val events = ArrayList<MjEvent>()

    // ------------------------------------------------ 开局

    fun newMatch(infos: List<PlayerInfo>, mode: MjMode) {
        require(infos.size == 4) { "麻将需要4名玩家" }
        this.mode = mode
        roundNumber++
        players.clear()
        infos.forEachIndexed { i, info -> players += PlayerState(info.copy(seat = i)) }
        if (randomSeed == null) {
            rng = Random(System.nanoTime() xor (roundNumber.toLong() shl 32) xor System.identityHashCode(this).toLong())
        }
        resetBoard()
    }

    private fun resetBoard() {
        phase = if (mode == MjMode.SICHUAN) MjPhase.DINGQUE else MjPhase.PLAYING
        result = null
        events.clear()
        huOrder.clear()
        fanLabels.clear()
        gangDelta.clear()
        rivers.forEach { it.clear() }
        meldsOf.forEach { it.clear() }
        for (i in 0 until 4) { dingque[i] = -1; swapPick[i].clear() }
        swapDir = -1
        claimCtxTile = null; claimCtxSeat = -1
        robGangSeat = -1; robGangCode = -1; robGangMeldIdx = -1
        claimOptions.clear(); claimResponded.clear()
        drawCount = IntArray(4)
        totalDiscards = 0
        lastDrawGang = false
        lastDrawSea = false
        lastDrawnId = -1

        val deck = MjTile.fullDeck(mode.withZi).shuffled(rng)
        dealer = rng.nextInt(4)
        hands.forEach { it.clear() }
        for (s in 0 until 4) {
            hands[s] += deck.subList(s * 13, s * 13 + 13)
            hands[s].sortBy { it.code }   // 内部初始序；对外展示走 myHand 确定性排序
        }
        wall.clear()
        wall.addAll(deck.subList(52, deck.size))
        // 掷骰切墙：两颗骰子点数之和决定起抓切割位（仪式与随机性统一，点数同步到 UI 展示）
        dice1 = rng.nextInt(6) + 1
        dice2 = rng.nextInt(6) + 1
        repeat((dice1 + dice2) % wall.size) { wall.addLast(wall.removeFirst()) }
        turn = dealer
        awaitingDiscard = false
        events += MjEvent.Shuffle
        if (mode == MjMode.SICHUAN) {
            scheduleAiSetup()
        } else {
            drawTile(dealer)
        }
    }

    /** AI 定缺/换三张调度钩子（由 VM/Driver 覆盖为延时调度） */
    var aiSetupHook: (() -> Unit)? = null
    private fun scheduleAiSetup() { aiSetupHook?.invoke() }

    // ------------------------------------------------ 手牌访问

    private var hands = Array(4) { ArrayList<MjTile>() }

    fun myHand(s: Int): List<MjTile> =
        hands[s].sortedWith(compareBy({ it.code }, { it.id }))   // 确定性排序：同 code 不跳动

    fun handCount(s: Int): Int = hands[s].size

    /** 癞子局：该牌是否癞子 */
    fun isLaizi(t: MjTile): Boolean = jokerCode >= 0 && t.code == jokerCode

    /** 癞子局：手中癞子数 */
    fun jokerCount(s: Int): Int =
        if (jokerCode < 0) 0 else hands[s].count { it.code == jokerCode }

    /** 手 + 副露占用的花色数（川麻缺一门判定） */
    fun suitCount(s: Int): Int {
        val set = HashSet<Int>()
        hands[s].forEach { set.add(it.code / 9) }
        meldsOf[s].forEach { m -> set.add(m.code / 9) }
        set.remove(3)   // 川麻无字牌，防御
        return set.size
    }

    // ------------------------------------------------ 川麻：定缺 / 换三张

    fun canDingque(s: Int): Boolean = phase == MjPhase.DINGQUE && dingque[s] < 0

    fun dingqueSuit(s: Int, suit: Int): Boolean {
        if (!canDingque(s)) return false
        if (suit !in 0..2) return false
        dingque[s] = suit
        events += MjEvent.DingQue(s, suit)
        if (dingque.all { it >= 0 }) {
            phase = MjPhase.SWAP3
            scheduleAiSetup()
        }
        return true
    }

    fun canSwap(s: Int): Boolean = phase == MjPhase.SWAP3 && swapPick[s].isEmpty()

    /** 提交换三张（须为同花色 3 张）；全员提交后执行交换 */
    fun submitSwap(s: Int, tileIds: List<Int>): Boolean {
        if (!canSwap(s)) return false
        val tiles = tileIds.mapNotNull { id -> hands[s].firstOrNull { it.id == id } }
        if (tiles.size != 3 || tiles.map { it.suit }.toSet().size != 1) return false
        swapPick[s] = ArrayList(tiles)
        if (swapPick.all { it.size == 3 }) doSwap()
        return true
    }

    private fun doSwap() {
        swapDir = rng.nextInt(3) + 1   // 1 下家 / 2 对家 / 3 上家
        val given = Array(4) { s -> swapPick[s].toList() }
        for (s in 0 until 4) {
            val to = (s + swapDir) % 4
            given[s].forEach { t -> hands[s].removeAll { it.id == t.id } }
            hands[to] += given[s]
        }
        hands.forEach { it.sortWith(compareBy({ t: MjTile -> t.code }, { t: MjTile -> t.id })) }
        swapPick.forEach { it.clear() }
        events += MjEvent.SwapDone(swapDir)
        phase = MjPhase.PLAYING
        drawTile(dealer)
    }

    // ------------------------------------------------ 摸牌 / 出牌

    private fun drawTile(s: Int) {
        if (phase != MjPhase.PLAYING || huOrder.contains(s)) return
        if (wall.isEmpty()) { doLiuJu(); return }
        lastDrawGang = false
        lastDrawSea = false
        val t = wall.removeFirst()
        hands[s] += t
        lastDrawnId = t.id
        drawCount[s]++
        turn = s
        awaitingDiscard = true
        if (wall.isEmpty()) lastDrawSea = true
        events += MjEvent.Draw(s)
    }

    private fun drawTail(s: Int, afterGang: Boolean) {
        if (phase != MjPhase.PLAYING) return
        if (wall.isEmpty()) {
            // 杠后无牌可摸：直接进入弃牌
            lastDrawnId = -1
            turn = s; awaitingDiscard = true
            return
        }
        val t = wall.removeLast()
        hands[s] += t
        lastDrawnId = t.id
        drawCount[s]++
        turn = s
        awaitingDiscard = true
        lastDrawGang = afterGang
        lastDrawSea = wall.isEmpty()
        events += MjEvent.Draw(s)
    }

    /** 是否轮到 [s] 摸牌 */
    fun canDraw(s: Int): Boolean =
        phase == MjPhase.PLAYING && turn == s && !awaitingDiscard && !huOrder.contains(s)

    /** 轮到 [s] 且处于待弃牌状态 */
    fun canDiscardPhase(s: Int): Boolean =
        phase == MjPhase.PLAYING && turn == s && awaitingDiscard && !huOrder.contains(s)

    /** 出牌合法性（UI 预检用） */
    fun canDiscard(s: Int, tileId: Int): Boolean {
        if (!canDiscardPhase(s)) return false
        val t = hands[s].firstOrNull { it.id == tileId } ?: return false
        if (isLaizi(t)) return false                      // 癞子不能打
        if (mode == MjMode.SICHUAN) {
            val q = dingque[s]
            // 有缺必打缺
            if (q in 0..2 && hands[s].any { it.code / 9 == q }) {
                if (t.code / 9 != q) return false
            }
        }
        return true
    }

    /** 自摸胡 */
    fun canSelfHu(s: Int): Boolean {
        if (!canDiscardPhase(s)) return false
        if (mode == MjMode.SICHUAN && suitCount(s) > 2) return false
        return winCheck(s) != null
    }

    private fun winCheck(s: Int): MjRules.MjWinInfo? {
        val cnt = IntArray(34)
        hands[s].forEach { if (it.code != jokerCode) cnt[it.code]++ }
        val jokers = jokerCount(s)
        return MjRules.canWin(
            cnt, jokers,
            allowSevenPairs = true, allowThirteen = !mode.selfDrawOnly || true,
            requireTwoSuits = mode == MjMode.SICHUAN
        )
    }

    /** 暗杠：手中 4 张同 code（癞子局癞子不可杠） */
    fun anGangOptions(s: Int): List<Int> {
        if (!canDiscardPhase(s)) return emptyList()
        return hands[s].groupBy { it.code }
            .filter { it.key != jokerCode && it.value.size == 4 }
            .keys.toList()
    }

    /** 补杠：已碰的 code 又摸到第 4 张 */
    fun buGangOptions(s: Int): List<MjTile> {
        if (!canDiscardPhase(s)) return emptyList()
        val res = ArrayList<MjTile>()
        meldsOf[s].filter { it.type == MjMeldType.PENG }.forEach { m ->
            hands[s].firstOrNull { it.code == m.code && it.code != jokerCode }?.let { res += it }
        }
        return res
    }

    fun discard(s: Int, tileId: Int): Boolean {
        if (!canDiscardPhase(s)) return false
        val t = hands[s].firstOrNull { it.id == tileId } ?: return false
        if (!canDiscard(s, tileId)) return false
        hands[s].removeAll { it.id == tileId }
        rivers[s] += t
        totalDiscards++
        awaitingDiscard = false
        lastDrawnId = -1
        events += MjEvent.Discard(s, t)
        openClaimWindow(t, s, robGang = false)
        return true
    }

    fun discardFirst(s: Int): MjTile? {
        if (!canDiscardPhase(s)) return null
        val q = dingque[s]
        val t = if (mode == MjMode.SICHUAN && q in 0..2) {
            hands[s].firstOrNull { it.code / 9 == q }
        } else hands[s].firstOrNull { it.code != jokerCode }
        return t
    }

    // ------------------------------------------------ 宣告窗口

    private fun openClaimWindow(t: MjTile, from: Int, robGang: Boolean, gangMeldIdx: Int = -1) {
        claimCtxTile = t
        claimCtxSeat = from
        robGangSeat = if (robGang) from else -1
        robGangCode = if (robGang) t.code else -1
        robGangMeldIdx = gangMeldIdx
        claimOptions.clear()
        claimResponded.clear()
        for (s in 0 until 4) {
            if (s == from || huOrder.contains(s)) continue
            val opts = claimOptionsFor(s, t, robGang)
            if (opts.isNotEmpty()) claimOptions[s] = opts
        }
        if (claimOptions.isEmpty()) {
            closeClaimWindowNoTakers()
        }
    }

    private fun claimOptionsFor(s: Int, t: MjTile, robGang: Boolean): List<MjClaimOpt> {
        val res = ArrayList<MjClaimOpt>()
        val cnt = IntArray(34)
        hands[s].forEach { if (it.code != jokerCode) cnt[it.code]++ }
        val jokers = jokerCount(s)
        val robHu = robGang
        // 胡（四川不点炮：仅抢杠也不允许 → 川麻宣告窗只处理碰杠）
        if (!mode.selfDrawOnly || robHu) {
            cnt[t.code]++
            if (MjRules.canWin(cnt, jokers, requireTwoSuits = mode == MjMode.SICHUAN) != null) {
                if (!robHu || true) res += MjClaimOpt("HU", label = "胡")
            }
            cnt[t.code]--
        }
        if (robGang) return res      // 抢杠窗只允许胡
        // 碰 / 明杠
        val real = cnt[t.code]
        if (real >= 2) res += MjClaimOpt("PENG", label = "碰")
        if (real >= 3) res += MjClaimOpt("GANG", label = "杠")
        // 吃（仅大众/癞子；仅上家）——顺子三张必须同花色（pos 边界严格钳制，
        // 修复 8万9万+1筒 / 9万+1筒 等跨花色连号被误判为顺子的 bug）
        if (!mode.selfDrawOnly && t.code < 27 && s == (claimCtxSeat + 1) % 4) {
            val pos = t.code % 9
            // t 为首：t+1、t+2 须仍在同花色内（pos+2 ≤ 8）
            if (pos <= 6 && cnt[t.code + 1] > 0 && cnt[t.code + 2] > 0)
                res += MjClaimOpt("CHI", chiMid = t.code + 1, label = "吃")
            // t 为中：t-1、t+1 须同花色（pos-1 ≥ 0 且 pos+1 ≤ 8）
            if (pos in 1..7 && cnt[t.code - 1] > 0 && cnt[t.code + 1] > 0)
                res += MjClaimOpt("CHI", chiMid = t.code, label = "吃")
            // t 为尾：t-2、t-1 须同花色（pos-2 ≥ 0）
            if (pos >= 2 && cnt[t.code - 2] > 0 && cnt[t.code - 1] > 0)
                res += MjClaimOpt("CHI", chiMid = t.code - 1, label = "吃")
        }
        return res.distinctBy { it.kind + it.chiMid }
    }

    /** 当前有宣告资格的座位 */
    val pendingClaimSeats: List<Int>
        get() = claimOptions.keys.filter { !claimResponded.containsKey(it) }

    /** [s] 的可选宣告（无窗/未轮到返回空） */
    fun claimsFor(s: Int): List<MjClaimOpt> =
        if (claimResponded.containsKey(s)) emptyList() else claimOptions[s] ?: emptyList()

    /** 宣告回应：opt=null 表示过。全员表态后自动裁决 */
    fun respondClaim(s: Int, opt: MjClaimOpt?): Boolean {
        if (!claimOptions.containsKey(s)) return false
        if (claimResponded.containsKey(s)) return false
        if (opt != null && claimOptions[s]?.none {
                it.kind == opt.kind && it.chiMid == opt.chiMid
            } == true) return false
        claimResponded[s] = opt
        if (claimResponded.keys.containsAll(claimOptions.keys)) resolveClaims()
        return true
    }

    private fun resolveClaims() {
        val tile = claimCtxTile ?: return
        val from = claimCtxSeat
        val huSeats = claimResponded.filter { it.value?.kind == "HU" }.keys.sortedBy {
            (it - from + 4) % 4
        }
        if (huSeats.isNotEmpty()) {
            if (robGangSeat >= 0) abortRobGang()
            // 点炮胡：胡牌张留在放炮者牌河（显示约定），计入胡牌者牌型
            for (ws in huSeats) settleHu(ws, tile, selfDraw = false, robGang = robGangSeat >= 0)
            closeCtx()
            if (phase != MjPhase.GAME_OVER) advanceDraw()
            return
        }
        // 碰/杠/吃按离出牌者近者优先
        val take = claimResponded.entries
            .filter { it.value?.kind == "PENG" || it.value?.kind == "GANG" || it.value?.kind == "CHI" }
            .minByOrNull { (it.key - from + 4) % 4 }
        if (take == null && pendingBu != null) {
            // 抢杠窗无人胡 → 完成补杠
            closeCtx()
            finishPendingBu()
            return
        }
        if (take != null) {
            val seat = take.key
            when (take.value!!.kind) {
                "PENG" -> {
                    rivers[from].removeAll { it.id == tile.id }
                    val used = hands[seat].filter { it.code == tile.code }.take(2)
                    used.forEach { u -> hands[seat].removeAll { it.id == u.id } }
                    meldsOf[seat] += MjMeld(MjMeldType.PENG, used + tile, from)
                    events += MjEvent.Peng(seat)
                    lastDrawnId = -1
                    turn = seat; awaitingDiscard = true
                }
                "GANG" -> {
                    rivers[from].removeAll { it.id == tile.id }
                    val used = hands[seat].filter { it.code == tile.code }.take(3)
                    used.forEach { u -> hands[seat].removeAll { it.id == u.id } }
                    meldsOf[seat] += MjMeld(MjMeldType.GANG_MING, used + tile, from)
                    events += MjEvent.Gang(seat, MjMeldType.GANG_MING)
                    payGang(seat, MjMeldType.GANG_MING, from)
                    closeCtx()
                    drawTail(seat, afterGang = true)
                    return
                }
                "CHI" -> {
                    rivers[from].removeAll { it.id == tile.id }
                    val mid = take.value!!.chiMid
                    val seq = chiTiles(hands[seat], mid, tile.code)
                    seq.forEach { u -> hands[seat].removeAll { it.id == u.id } }
                    meldsOf[seat] += MjMeld(MjMeldType.CHI, seq + tile, from)
                    events += MjEvent.Chi(seat)
                    lastDrawnId = -1
                    turn = seat; awaitingDiscard = true
                }
            }
            closeCtx()
            return
        }
        closeClaimWindowNoTakers()
    }

    /** 无人宣告：下家摸牌（血战跳过已胡者） */
    private fun closeClaimWindowNoTakers() {
        closeCtx()
        advanceDraw()
    }

    private fun advanceDraw() {
        if (phase == MjPhase.GAME_OVER) return
        var next = (turn + 1) % 4
        var guard = 0
        while (huOrder.contains(next) && guard < 4) { next = (next + 1) % 4; guard++ }
        if (huOrder.contains(next)) { doLiuJu(); return }
        turn = next
        drawTile(next)
    }

    private fun closeCtx() {
        claimCtxTile = null
        claimCtxSeat = -1
        robGangSeat = -1; robGangCode = -1; robGangMeldIdx = -1
        claimOptions.clear()
        claimResponded.clear()
    }

    /** 吃进的两张手牌（顺子三张中除去他人打出的那张） */
    private fun chiTiles(hand: List<MjTile>, midCode: Int, claimCode: Int): List<MjTile> {
        val need = listOf(midCode - 1, midCode, midCode + 1) - claimCode
        return need.mapNotNull { c -> hand.firstOrNull { it.code == c } }
    }

    // ------------------------------------------------ 杠

    fun declareAnGang(s: Int, code: Int): Boolean {
        if (!canDiscardPhase(s)) return false
        val four = hands[s].filter { it.code == code }
        if (four.size != 4 || code == jokerCode) return false
        four.forEach { t -> hands[s].removeAll { it.id == t.id } }
        meldsOf[s] += MjMeld(MjMeldType.GANG_AN, four, -1)
        events += MjEvent.Gang(s, MjMeldType.GANG_AN)
        payGang(s, MjMeldType.GANG_AN, -1)
        drawTail(s, afterGang = true)
        return true
    }

    fun declareBuGang(s: Int, tileId: Int): Boolean {
        if (!canDiscardPhase(s)) return false
        val t = hands[s].firstOrNull { it.id == tileId } ?: return false
        if (t.code == jokerCode) return false
        val idx = meldsOf[s].indexOfFirst { it.type == MjMeldType.PENG && it.code == t.code }
        if (idx < 0) return false
        hands[s].removeAll { it.id == tileId }
        // 抢杠宣告窗（点炮规则下无抢杠胡，直接完成）
        if (mode.selfDrawOnly) {
            finishBuGang(s, idx, t)
            return true
        }
        openBuGangWindow(s, idx, t)
        return true
    }

    private fun openBuGangWindow(s: Int, idx: Int, t: MjTile) {
        pendingBu = PendingBu(s, idx, t)
        claimCtxTile = t
        claimCtxSeat = s
        robGangSeat = s
        robGangCode = t.code
        robGangMeldIdx = idx
        claimOptions.clear()
        claimResponded.clear()
        for (o in 0 until 4) {
            if (o == s || huOrder.contains(o)) continue
            val opts = claimOptionsFor(o, t, robGang = true)
            if (opts.isNotEmpty()) claimOptions[o] = opts
        }
        if (claimOptions.isEmpty()) finishPendingBu()
    }

    private var pendingBu: PendingBu? = null
    private class PendingBu(val seat: Int, val meldIdx: Int, val tile: MjTile)

    private fun finishPendingBu() {
        val bu = pendingBu ?: return
        pendingBu = null
        finishBuGang(bu.seat, bu.meldIdx, bu.tile)
    }

    private fun finishBuGang(s: Int, idx: Int, t: MjTile) {
        val m = meldsOf[s][idx]
        meldsOf[s][idx] = MjMeld(MjMeldType.GANG_BU, m.tiles + t, m.from)
        events += MjEvent.Gang(s, MjMeldType.GANG_BU)
        payGang(s, MjMeldType.GANG_BU, -1)
        closeCtx()
        drawTail(s, afterGang = true)
    }

    private fun abortRobGang() {
        val bu = pendingBu ?: return
        pendingBu = null
        // 抢杠成立：杠牌退还补杠者手牌（作为和牌张随胡结算，不入副露）
        hands[bu.seat] += bu.tile
        hands[bu.seat].sortWith(compareBy({ t: MjTile -> t.code }, { t: MjTile -> t.id }))
    }

    /** 刮风下雨：明杠（点杠者 1 倍）/ 补杠（三家各 1）/ 暗杠（三家各 2） */
    private fun payGang(s: Int, type: MjMeldType, from: Int) {
        if (mode != MjMode.SICHUAN || phase == MjPhase.GAME_OVER) return
        val base = mode.baseScore
        when (type) {
            MjMeldType.GANG_MING -> {
                addGangDelta(s, +base)
                addGangDelta(from, -base)
            }
            MjMeldType.GANG_BU -> {
                for (o in 0 until 4) if (o != s && !huOrder.contains(o)) {
                    addGangDelta(s, +base)
                    addGangDelta(o, -base)
                }
            }
            MjMeldType.GANG_AN -> {
                for (o in 0 until 4) if (o != s && !huOrder.contains(o)) {
                    addGangDelta(s, +2 * base)
                    addGangDelta(o, -2 * base)
                }
            }
            else -> {}
        }
    }

    private fun addGangDelta(s: Int, v: Int) { gangDelta[s] = (gangDelta[s] ?: 0) + v }

    // ------------------------------------------------ 胡 / 结算

    /** 自摸胡宣告（摸牌后待出牌状态可调） */
    fun declareSelfHu(s: Int): Boolean {
        if (!canSelfHu(s)) return false
        settleHu(s, null, selfDraw = true, robGang = false)
        if (phase != MjPhase.GAME_OVER) advanceDraw()
        return true
    }

    /** 当前宣告窗内的牌（AI/提示评估用） */
    fun currentClaimTile(): MjTile? = claimCtxTile

    /** 当前宣告窗的供牌者 */
    fun currentClaimFrom(): Int = claimCtxSeat

    private fun settleHu(s: Int, winTile: MjTile?, selfDraw: Boolean, robGang: Boolean) {
        val cnt = IntArray(34)
        hands[s].forEach { if (it.code != jokerCode) cnt[it.code]++ }
        if (winTile != null && !selfDraw) cnt[winTile.code]++
        val jokers = jokerCount(s)
        val info = MjRules.canWin(cnt, jokers, requireTwoSuits = mode == MjMode.SICHUAN)
            ?: return
        val fr = when (mode) {
            MjMode.SICHUAN -> MjRules.fanSichuan(
                cnt, meldsOf[s], winTile ?: MjTile.byId(0)!!,
                gangDraw = lastDrawGang && selfDraw,
                dealerFirst = dealerFirstHu(s),
                firstDraw = drawCount[s] <= 1 && totalDiscards <= 1
            )
            else -> MjRules.fanDazhong(
                cnt, jokers, meldsOf[s], winTile ?: MjTile.byId(0)!!,
                selfDraw = selfDraw,
                gangDraw = lastDrawGang && selfDraw,
                robGang = robGang,
                lastWall = lastDrawSea && selfDraw,
                dealerFirst = dealerFirstHu(s),
                firstDraw = drawCount[s] <= 1 && totalDiscards <= 1,
                laizi = mode == MjMode.LAIZI
            )
        }
        val label = fr.fans.filter { it.second > 0 }.joinToString(" ") { it.first }
            .ifBlank { "平胡" }
        fanLabels[s] = label
        huOrder += s
        val value = mode.baseScore * fr.total
        val delta = HashMap<Int, Int>()
        for (p in 0 until 4) delta[p] = gangDelta[p] ?: 0
        if (selfDraw) {
            for (o in 0 until 4) if (o != s && !huOrder.contains(o)) {
                delta[s] = (delta[s] ?: 0) + value
                delta[o] = (delta[o] ?: 0) - value
            }
        } else {
            // 点炮（一炮多响：每次结算各自向放炮者收）
            delta[s] = (delta[s] ?: 0) + value
            delta[claimCtxSeat] = (delta[claimCtxSeat] ?: 0) - value
        }
        accumulate(delta)
        events += MjEvent.Hu(s, selfDraw, fr.fans.filter { it.second > 0 }.map { it.first }, fr.total, winTile)
        huDetails += MjHuDetail(s, fr.fans.filter { it.second > 0 }.map { it.first }, fr.total, selfDraw, winTile)
        if (mode.selfDrawOnly) {
            // 血战：三家胡齐或只剩一家即终局
            if (huOrder.size >= 3) endGame(note = "血战到底")
        } else {
            endGame(note = "")
        }
    }

    private fun dealerFirstHu(s: Int): Boolean =
        s == dealer && totalDiscards == 0 && drawCount[s] <= 1

    private val scoreAcc = HashMap<Int, Int>()
    private val huDetails = ArrayList<MjHuDetail>()

    private fun accumulate(delta: Map<Int, Int>) {
        for ((k, v) in delta) scoreAcc[k] = (scoreAcc[k] ?: 0) + v
    }

    private fun doLiuJu() {
        if (phase == MjPhase.GAME_OVER) return
        events += MjEvent.LiuJu
        if (mode == MjMode.SICHUAN) chaJiao()
        endGame(note = if (mode == MjMode.SICHUAN) "流局·查叫" else "流局")
    }

    /** 川麻流局查叫：花猪赔 8 倍/家，未听（大叫）赔已听者 4 倍/家 */
    private fun chaJiao() {
        val base = mode.baseScore
        val flower = ArrayList<Int>()
        val notTing = ArrayList<Int>()
        val ting = ArrayList<Int>()
        for (s in 0 until 4) {
            if (huOrder.contains(s)) continue
            val cnt = IntArray(34)
            hands[s].forEach { cnt[it.code]++ }
            meldsOf[s].forEach { m -> cnt[m.code]++ }
            val suits = (0 until 3).count { k -> (0 until 9).any { cnt[k * 9 + it] > 0 } }
            if (suits == 3) { flower += s; continue }
            val t = MjRules.tingCodes(cnt, 0, allowSevenPairs = true,
                allowThirteen = false, requireTwoSuits = true, onlyNumber = true)
            if (t.isEmpty()) notTing += s else ting += s
        }
        for (f in flower) {
            for (o in 0 until 4) if (o != f) {
                addGangDelta(f, -8 * base)
                addGangDelta(o, +8 * base)
            }
        }
        for (n in notTing) {
            for (o in ting) {
                addGangDelta(n, -4 * base)
                addGangDelta(o, +4 * base)
            }
        }
    }

    private fun endGame(note: String) {
        phase = MjPhase.GAME_OVER
        val delta = HashMap<Int, Int>()
        for (s in 0 until 4) delta[s] = scoreAcc[s] ?: 0
        for ((k, v) in gangDelta) delta[k] = (delta[k] ?: 0) + v
        result = MjResult(
            scoreDelta = delta,
            huOrder = huOrder.toList(),
            liuju = note.startsWith("流局"),
            details = huDetails.toList(),
            gangDelta = gangDelta.toMap(),
            note = note
        )
        events += MjEvent.GameOver
    }

    // ------------------------------------------------ 快照

    fun snapshotFor(s: Int): MjSnapshot {
        val views = (0 until 4).map { i ->
            val p = players[i]
            MjSeatView(
                seat = i,
                name = p.info.name,
                avatar = p.info.avatar,
                isAi = p.info.isAi,
                isTurn = phase == MjPhase.PLAYING && turn == i,
                handCount = hands[i].size,
                hand = if (i == s) myHand(i) else emptyList(),
                melds = meldsOf[i].toList(),
                river = rivers[i].toList(),
                dingque = if (phase == MjPhase.DINGQUE && i != s) -1 else dingque[i],
                huRank = huOrder.indexOf(i).let { if (it >= 0) it + 1 else 0 },
                fanLabel = fanLabels[i] ?: "",
                ting = if (huOrder.contains(i)) true else isTing(i),
                isDealer = i == dealer
            )
        }
        val myClaims = if (phase == MjPhase.PLAYING && claimOptions.containsKey(s) &&
            !claimResponded.containsKey(s)) claimOptions[s] ?: emptyList() else emptyList()
        val myTurnToAct = phase == MjPhase.PLAYING && turn == s && awaitingDiscard
        return MjSnapshot(
            mode = mode,
            phase = phase,
            round = roundNumber,
            dealer = dealer,
            turn = turn,
            awaitingDiscard = awaitingDiscard,
            wallCount = wall.size,
            seats = views,
            lastDiscardSeat = claimCtxSeat.takeIf { robGangSeat < 0 } ?: -1,
            lastDiscardTile = claimCtxTile?.takeIf { robGangSeat < 0 },
            laiziCode = jokerCode,
            myClaims = myClaims,
            swapDir = swapDir,
            swapPicked = phase == MjPhase.SWAP3 && swapPick[s].size == 3,
            gangDelta = gangDelta.toMap(),
            myHu = myTurnToAct && canSelfHu(s),
            drawnTileId = if (phase == MjPhase.PLAYING && turn == s && awaitingDiscard) lastDrawnId else -1,
            anGangCodes = if (myTurnToAct) anGangOptions(s) else emptyList(),
            buGangIds = if (myTurnToAct) buGangOptions(s).map { it.id } else emptyList(),
            dice1 = dice1,
            dice2 = dice2,
            result = result
        )
    }

    /** 听牌状态（用于角标与查叫展示） */
    fun isTing(s: Int): Boolean {
        if (phase != MjPhase.PLAYING || huOrder.contains(s)) return false
        if (mode == MjMode.SICHUAN && suitCount(s) > 2) return false
        val cnt = IntArray(34)
        hands[s].forEach { if (it.code != jokerCode) cnt[it.code]++ }
        val jokers = jokerCount(s)
        val n = cnt.sum() + jokers
        // 13 张（未摸牌）或 14 张（待出）都可判断
        val work = cnt.copyOf()
        return MjRules.tingCodes(
            work, jokers, requireTwoSuits = mode == MjMode.SICHUAN,
            onlyNumber = mode == MjMode.SICHUAN
        ).isNotEmpty() || (n % 3 == 2 && MjRules.canWin(
            cnt, jokers, requireTwoSuits = mode == MjMode.SICHUAN
        ) != null)
    }
}
