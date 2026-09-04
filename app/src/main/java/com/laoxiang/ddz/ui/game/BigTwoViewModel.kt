package com.laoxiang.ddz.ui.game

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.laoxiang.ddz.LaoXiangApp
import com.laoxiang.ddz.audio.VoiceMap
import com.laoxiang.ddz.data.*
import com.laoxiang.ddz.util.Prefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 锄大地视图模型：单机 vs AI（4人），结构与 PdkViewModel 平行。
 */
class BigTwoViewModel(app: Application) : AndroidViewModel(app) {

    private val sound = (app as LaoXiangApp).sound
    val prefs = Prefs(app)

    val snapshot = MutableStateFlow<BtSnapshot?>(null)
    val selected = MutableStateFlow<Set<Int>>(emptySet())
    val lastScore = MutableStateFlow(0)
    val fx = MutableSharedFlow<Fx>(extraBufferCapacity = 64)

    private val engine = BigTwoEngine()
    private var hintIndex = -1

    fun start() {
        selected.value = emptySet()
        hintIndex = -1
        val level = when (prefs.aiLevel) { 0 -> AiLevel.EASY; 2 -> AiLevel.HARD; else -> AiLevel.MEDIUM }
        val aiNames = listOf("铁锹叔", "山里红", "老埂头", "泥腿子")
        val aiAvatars = listOf(4, 7, 9, 6)
        val infos = (0 until 4).map { i ->
            if (i == 0) PlayerInfo(0, prefs.nickname.ifBlank { "我" }, prefs.avatar, false)
            else PlayerInfo(i, aiNames[i - 1] + level.label, aiAvatars[i - 1], true, level)
        }
        engine.newGame(infos)
        publish(effects = true)
        sound.startBgm()
        pumpAi()
    }

    private fun publish(effects: Boolean = false) {
        snapshot.value = engine.snapshotFor(0)
        if (effects) engine.events.mapNotNull { it.toFx() }.forEach { emit(it) }
        handleScore()
    }

    private fun BigTwoEngine.BtEvent.toFx(): Fx? = when (this) {
        is BigTwoEngine.BtEvent.Shuffle -> Fx.Shuffle
        is BigTwoEngine.BtEvent.Played -> Fx.Played(seat)
        is BigTwoEngine.BtEvent.Pass -> Fx.Pass(seat)
        is BigTwoEngine.BtEvent.NewRound -> Fx.NewRound(leader)
        is BigTwoEngine.BtEvent.GameOver -> Fx.GameOver(winner == 0, spring = false)
    }

    private fun emit(f: Fx) {
        fx.tryEmit(f)
        when (f) {
            is Fx.Shuffle -> sound.play("shuffle")
            is Fx.Played -> {
                val voice = VoiceMap.forBigTwo(snapshot.value?.lastMove)
                if (voice != null) sound.play(voice, 0.95f)
                sound.play("play", 0.35f)
            }
            is Fx.Pass -> sound.play("pass")
            is Fx.GameOver -> sound.play(if (f.landlordWon) "win" else "lose")
            else -> {}
        }
    }

    private fun pumpAi() {
        if (engine.phase != Phase.PLAYING) return
        val actor = engine.currentTurn
        if (actor <= 0) return
        val player = engine.players.getOrNull(actor) ?: return
        if (!player.info.isAi) return

        val ctx = BigTwoAi.Ctx(
            hand = engine.myHand(actor),
            lastMove = engine.lastMove,
            lastMoveSeat = engine.lastMoveSeat,
            handCounts = engine.players.map { it.info.seat to it.hand.size }.toMap(),
            playedCards = engine.players.flatMap { it.played }
        )
        val ai = BigTwoAi(actor, player.info.aiLevel ?: AiLevel.MEDIUM)
        val think = (when (player.info.aiLevel) {
            AiLevel.EASY -> 650; AiLevel.HARD -> 1150; else -> 900
        }) + Random.nextLong(450)

        viewModelScope.launch {
            delay(think)
            if (engine.phase != Phase.PLAYING || engine.currentTurn != actor) return@launch
            engine.events.clear()
            var acted = false
            val move = ai.chooseMove(ctx)
            if (move != null) {
                acted = engine.play(actor, move)
            }
            if (!acted && engine.hasTable()) {
                acted = engine.pass(actor)
            }
            if (!acted) {
                // 兜底：领出最小单张
                val hand = engine.myHand(actor)
                if (hand.isNotEmpty()) acted = engine.play(actor, listOf(hand.last()))
            }
            if (acted) {
                publish(effects = true)
                pumpAi()
            }
        }
    }

    // ------------------------------------------------ 本地玩家

    fun toggleSelect(cardId: Int) {
        val cur = selected.value
        selected.value = if (cardId in cur) cur - cardId else cur + cardId
        if (selected.value.isNotEmpty()) sound.play("select", 0.6f)
        hintIndex = -1
    }

    fun selectCards(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        val cur = selected.value
        val add = ids.filter { it !in cur }
        if (add.isEmpty()) return
        selected.value = cur + add
        sound.play("select", 0.45f)
        hintIndex = -1
    }

    fun clearSelection() { selected.value = emptySet(); hintIndex = -1 }

    fun playSelected(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != Phase.PLAYING || snap.turn != 0) return "还没轮到你"
        val ids = selected.value
        if (ids.isEmpty()) return "请先选牌"
        val me = snap.seats.first { it.seat == 0 }
        val cards = me.hand.filter { it.id in ids }
        val move = BtMove.of(cards) ?: return "不是有效牌型"
        if (!move.beats(snap.lastMove)) return "压不过上家"
        if (!firstLeadDone() && cards.none { it.rank == 3 && it.suit == CardSuit.DIAMOND }) {
            return "第一手必须带方块3"
        }
        engine.events.clear()
        if (!engine.play(0, cards)) return "出牌无效"
        clearSelection()
        publish(effects = true)
        pumpAi()
        return null
    }

    private fun firstLeadDone(): Boolean = engine.players.any { it.played.isNotEmpty() }

    fun passTurn(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != Phase.PLAYING || snap.turn != 0) return "还没轮到你"
        if (snap.lastMove == null) return "轮到你先出"
        engine.events.clear()
        if (!engine.pass(0)) return "不能过牌"
        clearSelection()
        publish(effects = true)
        pumpAi()
        return null
    }

    fun hint(): String? {
        val snap = snapshot.value ?: return "尚未开局"
        if (snap.phase != Phase.PLAYING || snap.turn != 0) return "还没轮到你"
        val hand = snap.seats.first { it.seat == 0 }.hand
        val all: List<List<Card>> = if (snap.lastMove == null) {
            // 领出提示：最小单张
            listOf(listOf(hand.minBy { it.rank }))
        } else {
            BigTwoAi.genBtBeats(hand, snap.lastMove!!)
        }
        if (all.isEmpty()) return "没有能出的牌"
        val ordered = all.sortedBy { it.sumOf { c -> c.rank } }
        hintIndex = (hintIndex + 1) % ordered.size
        selected.value = ordered[hintIndex].map { it.id }.toSet()
        return null
    }

    private var scoredRound = -1
    private fun handleScore() {
        val snap = snapshot.value ?: return
        val r = snap.result ?: return
        if (scoredRound == snap.round) return
        scoredRound = snap.round
        val myDelta = r.scoreDelta[0] ?: 0
        lastScore.value = myDelta
        prefs.addResult(myDelta > 0, myDelta)
    }

    fun exitGame() {
        sound.stopBgm()
        snapshot.value = null
        selected.value = emptySet()
    }


    /** 单机局快捷喊话：本地播语音（气泡由界面层显示） */
    fun localChat(phrase: String) {
        val code = CHAT_PHRASES.indexOf(phrase) + 1
        VoiceMap.forChat(code)?.let { sound.play(it, 1f) }
    }

    fun sfx(key: String, vol: Float = 1f) = sound.play(key, vol)
}
