package com.laoxiang.ddz.net

import com.laoxiang.ddz.data.AiLevel
import com.laoxiang.ddz.data.MjAi
import com.laoxiang.ddz.data.MjClaimOpt
import com.laoxiang.ddz.data.MjEngine
import com.laoxiang.ddz.data.MjEvent
import com.laoxiang.ddz.data.MjMode
import com.laoxiang.ddz.data.MjPhase
import com.laoxiang.ddz.data.MjSnapshot
import com.laoxiang.ddz.data.CardSuit
import com.laoxiang.ddz.data.PlayerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlin.random.Random

/**
 * 麻将房主侧驱动（v22）：大众 / 癞子 / 四川 三模式共用，
 * 行为与单机 MjViewModel 一致（引擎 + AI 泵 + 人类超时托管）。
 * 全部方法只在房主的游戏单线程上调用（GameHost 保证）。
 */
class MjDriver(
    private val mjMode: MjMode,
    private val gameScope: CoroutineScope,
    private val trigger: () -> Unit
) : HostDriver {

    override val gameId: String = mjMode.gameId
    private val engine = MjEngine()
    private var jobs = ArrayList<Job>()
    private var humanClaimJob: Job? = null
    private var humanSetupJob: Job? = null

    override fun newGame(infos: List<PlayerInfo>) {
        engine.newMatch(infos, mjMode)
        engine.aiSetupHook = { scheduleSetupAi() }
        scheduleSetupAi()
        scheduleHumanSetupFallback()
    }

    override fun aiNameFor(seat: Int): String =
        listOf("麻将老陈", "牌桌翠花", "巷口老王", "隔壁刘婶")[((seat - 1).coerceAtLeast(0)) % 4]

    override fun aiAvatarFor(seat: Int): Int = listOf(3, 6, 2, 8)[((seat - 1).coerceAtLeast(0)) % 4]

    override fun detachToAi(seat: Int, level: AiLevel) {
        engine.players.getOrNull(seat)?.let { p ->
            if (!p.info.isAi) p.info = p.info.copy(isAi = true, aiLevel = level)
        }
    }

    // ------------------------------------------------ 操作路由

    override fun play(seat: Int, ids: List<Int>): Boolean =
        ids.firstOrNull()?.let { engine.discard(seat, it) } ?: false

    override fun pass(seat: Int): Boolean = engine.respondClaim(seat, null)

    override fun claim(seat: Int, suit: CardSuit?): Boolean = false

    override fun mjAct(seat: Int, action: String, ids: List<Int>, extra: Int): Boolean {
        return when (action) {
            "discard" -> ids.firstOrNull()?.let { engine.discard(seat, it) } ?: false
            "hu" -> engine.respondClaim(seat, MjClaimOpt("HU"))
            "peng" -> engine.respondClaim(seat, MjClaimOpt("PENG"))
            "gang" -> engine.respondClaim(seat, MjClaimOpt("GANG"))
            "chi" -> engine.respondClaim(seat, MjClaimOpt("CHI", chiMid = extra))
            "gang_an" -> engine.declareAnGang(seat, extra)
            "gang_bu" -> ids.firstOrNull()?.let { engine.declareBuGang(seat, it) } ?: false
            "pass" -> engine.respondClaim(seat, null)
            "dingque" -> engine.dingqueSuit(seat, extra)
            "swap3" -> engine.submitSwap(seat, ids)
            else -> false
        }
    }

    override fun nextHand(): Boolean = false

    override fun restart(): Boolean {
        if (engine.phase != MjPhase.GAME_OVER) return false
        engine.newMatch(engine.players.map { it.info }, mjMode)
        engine.aiSetupHook = { scheduleSetupAi() }
        scheduleSetupAi()
        scheduleHumanSetupFallback()
        return true
    }

    // ------------------------------------------------ 快照 / 特效

    override fun snapshotFor(seat: Int): JsonElement =
        netJson.encodeToJsonElement(MjSnapshot.serializer(), engine.snapshotFor(seat))

    override fun drainEffects(): List<NetMsg.Effect> {
        val list = engine.events.mapNotNull { ev ->
            when (ev) {
                is MjEvent.Shuffle -> NetMsg.Effect("shuffle")
                is MjEvent.Discard -> NetMsg.Effect("discard", seat = ev.seat)
                is MjEvent.Chi -> NetMsg.Effect("chi", seat = ev.seat)
                is MjEvent.Peng -> NetMsg.Effect("peng", seat = ev.seat)
                is MjEvent.Gang -> NetMsg.Effect("gang", seat = ev.seat)
                is MjEvent.Hu -> NetMsg.Effect("hu", seat = ev.seat, rocket = ev.selfDraw)
                is MjEvent.LiuJu -> NetMsg.Effect("liuju")
                is MjEvent.GameOver -> NetMsg.Effect("game_over")
                else -> null
            }
        }
        engine.events.clear()
        return list
    }

    // ------------------------------------------------ AI 泵

    private fun thinkMs(level: AiLevel?): Long =
        (when (level) { AiLevel.EASY -> 650L; AiLevel.HARD -> 1150L; else -> 900L }) + Random.nextLong(450)

    private fun scheduleSetupAi() {
        for (s in 0 until 4) {
            val p = engine.players.getOrNull(s) ?: continue
            if (!p.info.isAi) continue
            if (engine.canDingque(s)) {
                val seat = s
                jobs += gameScope.launch {
                    delay(700 + Random.nextLong(600))
                    if (engine.canDingque(seat)) {
                        engine.dingqueSuit(
                            seat,
                            MjAi(seat, p.info.aiLevel ?: AiLevel.MEDIUM, engine.mode)
                                .chooseDingque(engine.myHand(seat))
                        )
                        trigger()
                    }
                }
            }
            if (engine.canSwap(s)) {
                val seat = s
                jobs += gameScope.launch {
                    delay(800 + Random.nextLong(700))
                    if (engine.canSwap(seat)) {
                        engine.submitSwap(
                            seat,
                            MjAi(seat, p.info.aiLevel ?: AiLevel.MEDIUM, engine.mode)
                                .chooseSwap(engine.myHand(seat), engine.dingque[seat])
                        )
                        trigger()
                    }
                }
            }
        }
    }

    /** 人类（房主）定缺/换三张超时兜底 */
    private fun scheduleHumanSetupFallback() {
        humanSetupJob?.cancel()
        humanSetupJob = gameScope.launch {
            delay(16000)
            var acted = false
            if (engine.canDingque(0)) {
                engine.dingqueSuit(0, MjAi(0, AiLevel.MEDIUM, engine.mode).chooseDingque(engine.myHand(0)))
                acted = true
            }
            if (engine.canSwap(0)) {
                engine.submitSwap(0, MjAi(0, AiLevel.MEDIUM, engine.mode).chooseSwap(engine.myHand(0), engine.dingque[0]))
                acted = true
            }
            if (acted) trigger()
        }
        jobs += humanSetupJob!!
    }

    override fun pumpAi() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        humanClaimJob = null
        if (engine.phase == MjPhase.GAME_OVER) return

        if (engine.phase == MjPhase.DINGQUE || engine.phase == MjPhase.SWAP3) {
            scheduleSetupAi()
            scheduleHumanSetupFallback()
            return
        }
        if (engine.phase != MjPhase.PLAYING) return

        // 宣告窗：AI 逐一表态；人类超时自动过
        val pending = engine.pendingClaimSeats
        if (pending.isNotEmpty()) {
            for (seat in pending) {
                val p = engine.players[seat]
                if (p.info.isAi) {
                    jobs += gameScope.launch {
                        delay(480 + Random.nextLong(520))
                        val opts = engine.claimsFor(seat)
                        if (opts.isEmpty()) return@launch
                        val tile = engine.currentClaimTile()
                        val opt = tile?.let {
                            MjAi(seat, p.info.aiLevel ?: AiLevel.MEDIUM, engine.mode)
                                .chooseClaim(opts, engine.myHand(seat), engine.meldsOf[seat], engine.dingque[seat], it)
                        }
                        if (engine.respondClaim(seat, opt)) trigger()
                    }
                } else if (seat == 0 && humanClaimJob == null) {
                    humanClaimJob = gameScope.launch {
                        delay(11000)
                        if (engine.claimsFor(0).isNotEmpty()) {
                            engine.respondClaim(0, null)
                            trigger()
                        }
                    }
                    jobs += humanClaimJob!!
                }
            }
            return
        }

        // 出牌
        if (engine.canDiscardPhase(engine.turn)) {
            val actor = engine.turn
            val p = engine.players.getOrNull(actor) ?: return
            if (!p.info.isAi) return
            jobs += gameScope.launch {
                delay(thinkMs(p.info.aiLevel))
                if (!engine.canDiscardPhase(actor) || !p.info.isAi) return@launch
                val ai = MjAi(actor, p.info.aiLevel ?: AiLevel.MEDIUM, engine.mode)
                val act = ai.chooseSelfAction(
                    engine.myHand(actor), engine.meldsOf[actor], engine.dingque[actor],
                    canHu = engine.canSelfHu(actor),
                    anGangCodes = engine.anGangOptions(actor),
                    buGangTiles = engine.buGangOptions(actor)
                )
                var acted = false
                when (act.type) {
                    "HU" -> acted = engine.declareSelfHu(actor)
                    "GANG_AN" -> acted = engine.declareAnGang(actor, act.code)
                    "GANG_BU" -> acted = engine.declareBuGang(actor, act.tileId)
                    else -> {
                        val t = act.tileId.takeIf { it >= 0 }
                            ?.let { id -> engine.myHand(actor).firstOrNull { it.id == id } }
                            ?: engine.discardFirst(actor)
                        acted = t != null && engine.discard(actor, t.id)
                    }
                }
                if (acted) trigger()
            }
        }
    }

    override fun cancelJobs() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        humanClaimJob = null
        humanSetupJob = null
    }
}
