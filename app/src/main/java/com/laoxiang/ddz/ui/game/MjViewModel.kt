package com.laoxiang.ddz.ui.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laoxiang.ddz.LaoXiangApp
import com.laoxiang.ddz.audio.VoiceMap
import com.laoxiang.ddz.data.AiLevel
import com.laoxiang.ddz.data.MjAi
import com.laoxiang.ddz.data.MjClaimOpt
import com.laoxiang.ddz.data.MjEngine
import com.laoxiang.ddz.data.MjEvent
import com.laoxiang.ddz.data.MjMode
import com.laoxiang.ddz.data.MjPhase
import com.laoxiang.ddz.data.MjSnapshot
import com.laoxiang.ddz.data.MjTile
import com.laoxiang.ddz.data.PlayerInfo
import com.laoxiang.ddz.net.NetLobby
import com.laoxiang.ddz.net.NetMsg
import com.laoxiang.ddz.net.netJson
import com.laoxiang.ddz.util.Prefs
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlin.random.Random

/** 麻将一次性特效/音效事件 */
sealed class MjFx {
    object Shuffle : MjFx()
    data class Discard(val seat: Int, val tile: MjTile?) : MjFx()
    data class Claim(val seat: Int, val kind: String) : MjFx()
    data class Hu(val seat: Int, val selfDraw: Boolean, val fans: List<String> = emptyList()) : MjFx()
    object LiuJu : MjFx()
    object GameOver : MjFx()
}

/**
 * 麻将视图模型：单机 vs 三档 AI，与局域网联机（HOST=房主权威 / CLIENT=回显快照）。
 * 大众 / 癞子 / 四川 三模式共用，行为差异全部收敛在引擎与 AI 内。
 */
class MjViewModel(app: Application) : AndroidViewModel(app) {

    private val sound = (app as LaoXiangApp).sound
    val prefs = Prefs(app)

    val snapshot = MutableStateFlow<MjSnapshot?>(null)
    val selected = MutableStateFlow<Set<Int>>(emptySet())
    val lastScore = MutableStateFlow(0)
    val fx = MutableSharedFlow<MjFx>(extraBufferCapacity = 64)
    val mode = MutableStateFlow(GameMode.SINGLE)
    val mySeat = MutableStateFlow(0)
    val opNotice = MutableStateFlow<String?>(null)
    /** 当前局模式（联机由房间决定） */
    val mjMode = MutableStateFlow(MjMode.DAZHONG)

    fun clearOpNotice() { opNotice.value = null }

    private val engine = MjEngine()

    // ------------------------------------------------ 开局

    fun start(m: MjMode) {
        mode.value = GameMode.SINGLE
        mySeat.value = 0
        mjMode.value = m
        selected.value = emptySet()
        cancelJobs()
        val level = when (prefs.aiLevel) { 0 -> AiLevel.EASY; 2 -> AiLevel.HARD; else -> AiLevel.MEDIUM }
        val names = listOf("麻将老陈", "牌桌翠花", "巷口老王", "隔壁刘婶")
        val avatars = listOf(3, 6, 2, 8)
        val infos = (0 until 4).map { i ->
            if (i == 0) PlayerInfo(0, prefs.nickname.ifBlank { "我" }, prefs.avatar, false)
            else PlayerInfo(i, names[i - 1], avatars[i - 1], true, level)
        }
        engine.newMatch(infos, m)
        hookSetup()
        publish(effects = true)
        sound.startBgm()
        pump()
    }

    /** 单机：AI 定缺/换三张即时完成（在引擎钩子里） */
    private fun hookSetup() {
        engine.aiSetupHook = {
            for (s in 0 until 4) {
                if (s == 0) continue
                if (engine.canDingque(s)) {
                    engine.dingqueSuit(s, MjAi(s, levelOf(s), engine.mode).chooseDingque(engine.myHand(s)))
                }
            }
            if (engine.phase == MjPhase.SWAP3) {
                for (s in 0 until 4) {
                    if (s == 0) continue
                    if (engine.canSwap(s)) {
                        engine.submitSwap(s, MjAi(s, levelOf(s), engine.mode).chooseSwap(engine.myHand(s), engine.dingque[s]))
                    }
                }
            }
            // 人类座位超时自动定缺/换牌的调度
            scheduleHumanSetup()
        }
    }

    private fun levelOf(seat: Int): AiLevel =
        engine.players.getOrNull(seat)?.info?.aiLevel ?: AiLevel.MEDIUM

    // ------------------------------------------------ 发布与特效

    private fun publish(effects: Boolean = false) {
        snapshot.value = engine.snapshotFor(mySeat.value)
        if (effects) {
            engine.events.toList().forEach { ev ->
                when (ev) {
                    is MjEvent.Shuffle -> emit(MjFx.Shuffle)
                    is MjEvent.Discard -> emit(MjFx.Discard(ev.seat, ev.tile))
                    is MjEvent.Chi -> emit(MjFx.Claim(ev.seat, "CHI"))
                    is MjEvent.Peng -> emit(MjFx.Claim(ev.seat, "PENG"))
                    is MjEvent.Gang -> emit(MjFx.Claim(ev.seat, "GANG"))
                    is MjEvent.Hu -> emit(MjFx.Hu(ev.seat, ev.selfDraw, ev.fans))
                    is MjEvent.LiuJu -> emit(MjFx.LiuJu)
                    is MjEvent.GameOver -> emit(MjFx.GameOver)
                    else -> {}
                }
            }
            engine.events.clear()
        }
        handleScore()
    }

    private fun emit(f: MjFx) {
        fx.tryEmit(f)
        when (f) {
            is MjFx.Shuffle -> sound.play("shuffle")
            is MjFx.Discard -> {
                f.tile?.let { sound.play(VoiceMap.forMjTile(it), 0.95f) }
                sound.play("play", 0.35f)
            }
            is MjFx.Claim -> sound.play(VoiceMap.forMjAction(f.kind) ?: "play", 0.95f)
            is MjFx.Hu -> {
                sound.play(if (f.selfDraw) "voice_mj_zimo" else "voice_mj_hu", 1f)
                // 随后报番型（延迟避免叠音）
                val fanVoice = f.fans.firstNotNullOfOrNull { VoiceMap.forMjFan(it) }
                if (fanVoice != null) {
                    viewModelScope.launch {
                        delay(900)
                        sound.play(fanVoice, 0.95f)
                    }
                }
            }
            is MjFx.LiuJu -> sound.play("voice_mj_liuju", 1f)
            is MjFx.GameOver -> {}
        }
    }

    private var scoredRound = -1

    private fun handleScore() {
        val snap = snapshot.value ?: return
        val r = snap.result ?: return
        if (scoredRound == snap.round) return
        scoredRound = snap.round
        val myDelta = r.scoreDelta[mySeat.value] ?: 0
        lastScore.value = myDelta
        if (mode.value == GameMode.SINGLE || mode.value == GameMode.HOST) {
            prefs.addResult(myDelta > 0, myDelta)
        }
    }

    // ------------------------------------------------ AI 泵（单机）

    private val setupJobs = ArrayList<Job>()
    private val claimJobs = ArrayList<Job>()

    private fun cancelJobs() {
        setupJobs.forEach { it.cancel() }; setupJobs.clear()
        claimJobs.forEach { it.cancel() }; claimJobs.clear()
    }

    private fun think(level: AiLevel?): Long =
        (when (level) { AiLevel.EASY -> 620L; AiLevel.HARD -> 1100L; else -> 880L }) + Random.nextLong(420)

    /** 每次状态变化后调用：为 AI 排下行动，为人类排超时兜底 */
    private fun pump() {
        if (mode.value != GameMode.SINGLE) return
        val snap = snapshot.value ?: return
        when (snap.phase) {
            MjPhase.DINGQUE -> {
                val due = engine.players.filter { it.info.isAi && engine.canDingque(it.info.seat) }
                due.forEach { p ->
                    val seat = p.info.seat
                    setupJobs += viewModelScope.launch {
                        delay(think(p.info.aiLevel))
                        if (engine.canDingque(seat)) {
                            engine.dingqueSuit(seat, MjAi(seat, p.info.aiLevel ?: AiLevel.MEDIUM, engine.mode).chooseDingque(engine.myHand(seat)))
                            publish(effects = true)
                            pump()
                        }
                    }
                }
                scheduleHumanSetup()
            }
            MjPhase.SWAP3 -> {
                val due = engine.players.filter { it.info.isAi && engine.canSwap(it.info.seat) }
                due.forEach { p ->
                    val seat = p.info.seat
                    setupJobs += viewModelScope.launch {
                        delay(think(p.info.aiLevel))
                        if (engine.canSwap(seat)) {
                            engine.submitSwap(
                                seat,
                                MjAi(seat, p.info.aiLevel ?: AiLevel.MEDIUM, engine.mode)
                                    .chooseSwap(engine.myHand(seat), engine.dingque[seat])
                            )
                            publish(effects = true)
                            pump()
                        }
                    }
                }
                scheduleHumanSetup()
            }
            MjPhase.PLAYING -> {
                // 宣告窗
                val pend = engine.pendingClaimSeats
                if (pend.isNotEmpty()) {
                    pend.forEach { seat ->
                        val p = engine.players[seat]
                        if (p.info.isAi) {
                            claimJobs += viewModelScope.launch {
                                delay((500 + Random.nextLong(500)).coerceAtLeast(420))
                                if (engine.claimsFor(seat).isEmpty()) return@launch
                                val opts = engine.claimsFor(seat)
                                val tile = engine.currentClaimTile()
                                val opt = tile?.let {
                                    MjAi(seat, p.info.aiLevel ?: AiLevel.MEDIUM, engine.mode)
                                        .chooseClaim(opts, engine.myHand(seat), engine.meldsOf[seat], engine.dingque[seat], it)
                                }
                                engine.respondClaim(seat, opt)
                                publish(effects = true)
                                pump()
                            }
                        } else if (seat == mySeat.value && humanClaimTimeout == null) {
                            humanClaimTimeout = viewModelScope.launch {
                                delay(9500)
                                humanClaimTimeout = null
                                if (engine.claimsFor(mySeat.value).isNotEmpty()) {
                                    engine.respondClaim(mySeat.value, null)
                                    publish(effects = false)
                                    pump()
                                }
                            }
                            claimJobs += humanClaimTimeout!!
                        }
                    }
                    return
                }
                // 补杠抢杠窗也走 respondClaim（engine 内部处理），上面已覆盖
                // 出牌
                if (engine.canDiscardPhase(snap.turn)) {
                    val p = engine.players[snap.turn]
                    if (p.info.isAi) {
                        claimJobs += viewModelScope.launch {
                            delay(think(p.info.aiLevel))
                            if (!engine.canDiscardPhase(snap.turn) || !p.info.isAi) return@launch
                            aiAct(snap.turn, p.info.aiLevel ?: AiLevel.MEDIUM)
                            publish(effects = true)
                            pump()
                        }
                    }
                }
            }
            else -> {}
        }
    }

    private var humanClaimTimeout: Job? = null

    /** 人类定缺/换三张 15s 兜底 */
    private fun scheduleHumanSetup() {
        if (mode.value != GameMode.SINGLE) return
        val needDq = engine.canDingque(0)
        val needSwap = engine.canSwap(0)
        if (!needDq && !needSwap) return
        setupJobs += viewModelScope.launch {
            delay(15000)
            if (needDq && engine.canDingque(0)) {
                val suit = MjAi(0, AiLevel.MEDIUM, engine.mode).chooseDingque(engine.myHand(0))
                engine.dingqueSuit(0, suit)
            }
            if (needSwap && engine.canSwap(0)) {
                val ids = MjAi(0, AiLevel.MEDIUM, engine.mode).chooseSwap(engine.myHand(0), engine.dingque[0])
                engine.submitSwap(0, ids)
            }
            publish(effects = true)
            pump()
        }
    }

    private fun aiAct(seat: Int, level: AiLevel) {
        val ai = MjAi(seat, level, engine.mode)
        val act = ai.chooseSelfAction(
            engine.myHand(seat), engine.meldsOf[seat], engine.dingque[seat],
            canHu = engine.canSelfHu(seat),
            anGangCodes = engine.anGangOptions(seat),
            buGangTiles = engine.buGangOptions(seat)
        )
        when (act.type) {
            "HU" -> engine.declareSelfHu(seat)
            "GANG_AN" -> engine.declareAnGang(seat, act.code)
            "GANG_BU" -> engine.declareBuGang(seat, act.tileId)
            else -> {
                val t = act.tileId.takeIf { it >= 0 }?.let { id -> engine.myHand(seat).firstOrNull { it.id == id } }
                    ?: engine.discardFirst(seat)
                t?.let { engine.discard(seat, it.id) }
            }
        }
    }

    // ------------------------------------------------ 本地玩家操作

    fun toggleSelect(tileId: Int) {
        val cur = selected.value
        selected.value = if (tileId in cur) cur - tileId else cur + tileId
        sound.play("select", 0.6f)
    }

    fun clearSelection() { selected.value = emptySet() }

    /** 打出选中的牌（1 张） */
    fun discardSelected(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != MjPhase.PLAYING || snap.turn != mySeat.value || !snap.awaitingDiscard) return "还没轮到你"
        val ids = selected.value.toList()
        if (ids.size != 1) return "请选择一张牌"
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                if (!engine.discard(0, ids[0])) return "不能打这张"
                selected.value = emptySet()
                publish(effects = true)
                pump()
            }
            GameMode.HOST -> { NetLobby.sendMjAct("discard", ids, -1); selected.value = emptySet() }
            GameMode.CLIENT -> { NetLobby.sendMjAct("discard", ids, -1); selected.value = emptySet() }
        }
        return null
    }

    fun doClaim(opt: MjClaimOpt) {
        cancelClaimTimeout()
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                applyClaim(0, opt)
                publish(effects = true)
                pump()
            }
            else -> {
                val action = when (opt.kind) {
                    "HU" -> "hu"; "PENG" -> "peng"; "GANG" -> "gang"; else -> "chi"
                }
                NetLobby.sendMjAct(action, emptyList(), opt.chiMid)
            }
        }
        selected.value = emptySet()
    }

    fun passClaim() {
        cancelClaimTimeout()
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                engine.respondClaim(0, null)
                publish(effects = false)
                pump()
            }
            else -> NetLobby.sendMjAct("pass", emptyList(), -1)
        }
    }

    private fun cancelClaimTimeout() {
        humanClaimTimeout?.cancel()
        humanClaimTimeout = null
    }

    private fun applyClaim(seat: Int, opt: MjClaimOpt) {
        engine.respondClaim(seat, opt)
    }

    /** 暗杠 / 补杠（自己的回合按钮） */
    fun declareGang(code: Int, bu: Boolean, tileId: Int): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != MjPhase.PLAYING || snap.turn != mySeat.value) return "还没轮到你"
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                val ok = if (bu) engine.declareBuGang(0, tileId) else engine.declareAnGang(0, code)
                if (!ok) return "无法杠"
                publish(effects = true)
                pump()
            }
            else -> NetLobby.sendMjAct(if (bu) "gang_bu" else "gang_an", if (bu) listOf(tileId) else emptyList(), code)
        }
        return null
    }

    fun dingque(suit: Int) {
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                engine.dingqueSuit(0, suit)
                publish(effects = true)
                pump()
            }
            else -> NetLobby.sendMjAct("dingque", emptyList(), suit)
        }
    }

    fun confirmSwap(): String? {
        val ids = selected.value.toList()
        if (ids.size != 3) return "请选择同花色的 3 张"
        when (mode.value) {
            GameMode.SINGLE -> {
                engine.events.clear()
                if (!engine.submitSwap(0, ids)) return "换牌无效（需同花色 3 张）"
                selected.value = emptySet()
                publish(effects = true)
                pump()
            }
            else -> { NetLobby.sendMjAct("swap3", ids, -1); selected.value = emptySet() }
        }
        return null
    }

    /** 提示：高亮 AI 建议打的牌 / 建议换的三张（纯快照计算，单机联机通用） */
    fun hint(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        val me = mySeat.value
        val myView = snap.seats.getOrNull(me) ?: return "尚未开局"
        if (snap.myClaims.isNotEmpty()) return "请先选择 胡／碰／杠／吃／过"
        if (snap.phase == MjPhase.SWAP3 && !snap.swapPicked) {
            selected.value = MjAi(me, AiLevel.MEDIUM, snap.mode)
                .chooseSwap(myView.hand, myView.dingque).toSet()
            return null
        }
        if (snap.phase != MjPhase.PLAYING || snap.turn != me || !snap.awaitingDiscard) return "还没轮到你"
        if (myView.hand.isEmpty()) return "没有可出的牌"
        val t = MjAi(me, AiLevel.MEDIUM, snap.mode)
            .chooseDiscard(myView.hand, myView.melds, myView.dingque)
        selected.value = setOf(t.id)
        return null
    }

    // ------------------------------------------------ 联机

    private val netJobs = ArrayList<Job>()
    private var netBound = false

    fun bindNet() {
        if (netBound) return
        netBound = true
        mode.value = if (NetLobby.isHost && NetLobby.activeGame?.startsWith("mj") == true)
            GameMode.HOST else GameMode.CLIENT
        mySeat.value = NetLobby.mySeat.value
        netJobs += NetLobby.mySeat.onEach { mySeat.value = it }.launchIn(viewModelScope)
        netJobs += NetLobby.gSnapshot.onEach { msg ->
            if (msg?.game?.startsWith("mj") != true) return@onEach
            val snap = runCatching {
                netJson.decodeFromJsonElement(MjSnapshot.serializer(), msg.payload)
            }.getOrNull() ?: return@onEach
            mjMode.value = snap.mode
            snapshot.value = snap
            msg.effects.forEach { ef -> emitEffect(ef, snap) }
            handleScore()
        }.launchIn(viewModelScope)
        netJobs += NetLobby.chatFlow.onEach { c ->
            c?.let { (_, _, code) -> VoiceMap.forChat(code)?.let { sound.play(it, 0.95f) } }
        }.launchIn(viewModelScope)
    }

    private fun emitEffect(e: NetMsg.Effect, snap: MjSnapshot) {
        when (e.type) {
            "shuffle" -> emit(MjFx.Shuffle)
            "discard" -> {
                val t = snap.seats.getOrNull(e.seat)?.river?.lastOrNull()
                emit(MjFx.Discard(e.seat, t))
            }
            "chi" -> emit(MjFx.Claim(e.seat, "CHI"))
            "peng" -> emit(MjFx.Claim(e.seat, "PENG"))
            "gang" -> emit(MjFx.Claim(e.seat, "GANG"))
            "hu" -> emit(MjFx.Hu(e.seat, e.rocket))
            "liuju" -> emit(MjFx.LiuJu)
            "game_over" -> {}
        }
    }

    private fun awaitEcho(what: String, before: MjSnapshot, resend: () -> Unit) {
        if (mode.value != GameMode.CLIENT) return
        viewModelScope.launch {
            val first = withTimeoutOrNull(2500) {
                snapshot.first { s: MjSnapshot? -> s != null && (s.phase != before.phase || s.turn != before.turn || s.wallCount != before.wallCount || s.seats.any { sv -> before.seats.firstOrNull { b -> b.seat == sv.seat }?.handCount != sv.handCount }) }
            }
            if (first != null) return@launch
            resend()
            val second = withTimeoutOrNull(2200) {
                snapshot.first { s: MjSnapshot? -> s != null && s.seats.any { sv -> before.seats.firstOrNull { b -> b.seat == sv.seat }?.handCount != sv.handCount } }
            }
            if (second == null) opNotice.value = "网络不稳定，$what 没有送达，请稍候再试"
        }
    }

    /** 结算后再来一局 */
    fun again() {
        when (mode.value) {
            GameMode.SINGLE -> start(mjMode.value)
            GameMode.HOST -> NetLobby.hostRestart()
            GameMode.CLIENT -> NetLobby.sendRestart()
        }
    }

    fun exitGame() {
        if (mode.value != GameMode.SINGLE) {
            netJobs.forEach { it.cancel() }
            netJobs.clear()
            netBound = false
            NetLobby.leave()
        }
        cancelJobs()
        cancelClaimTimeout()
        mode.value = GameMode.SINGLE
        mySeat.value = 0
        scoredRound = -1
        sound.stopBgm()
        snapshot.value = null
        selected.value = emptySet()
    }

    fun localChat(phrase: String) {
        val code = CHAT_PHRASES.indexOf(phrase) + 1
        when (mode.value) {
            GameMode.SINGLE -> VoiceMap.forChat(code)?.let { sound.play(it, 1f) }
            else -> NetLobby.chat(phrase, code)
        }
    }

    fun sfx(key: String, vol: Float = 1f) = sound.play(key, vol)
}
