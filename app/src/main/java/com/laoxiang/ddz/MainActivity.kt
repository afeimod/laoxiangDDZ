package com.laoxiang.ddz

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoxiang.ddz.data.MjMode
import com.laoxiang.ddz.data.PdkMode
import com.laoxiang.ddz.net.NetLobby
import com.laoxiang.ddz.net.RoomBroadcast
import com.laoxiang.ddz.ui.collection.CollectionScreen
import com.laoxiang.ddz.ui.game.BigTwoGameScreen
import com.laoxiang.ddz.ui.game.BigTwoViewModel
import com.laoxiang.ddz.ui.game.GameMode
import com.laoxiang.ddz.ui.game.GameScreen
import com.laoxiang.ddz.ui.game.GameViewModel
import com.laoxiang.ddz.ui.game.MjGameScreen
import com.laoxiang.ddz.ui.game.MjViewModel
import com.laoxiang.ddz.ui.game.GuandanGameScreen
import com.laoxiang.ddz.ui.game.GuandanViewModel
import com.laoxiang.ddz.ui.game.PdkGameScreen
import com.laoxiang.ddz.ui.game.PdkViewModel
import com.laoxiang.ddz.ui.game.ShengjiGameScreen
import com.laoxiang.ddz.ui.game.ShengjiViewModel
import com.laoxiang.ddz.ui.lobby.LobbyScreen
import com.laoxiang.ddz.ui.result.PdkResultScreen
import com.laoxiang.ddz.ui.result.ResultScreen
import com.laoxiang.ddz.ui.room.NetRoomUi
import com.laoxiang.ddz.ui.room.RoomScreen
import com.laoxiang.ddz.ui.room.NET_GAMES
import com.laoxiang.ddz.ui.room.parseNetGame
import com.laoxiang.ddz.ui.theme.LaoXiangDDZTheme

/**
 * 老乡斗地主 主界面
 * 页面流：大厅 →（房间 | 全系列对局 | 棋牌合集 → 各对局）→ 结算 → 返回
 * v20：本地联机扩展到全系列纸牌（掼蛋/升级/跑得快/锄大地走 NetLobby；斗地主沿用原链路）
 * 全屏沉浸：隐藏状态栏与导航栏，从屏幕边缘上/下滑可临时呼出
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        // 刘海/挖孔屏也全屏延伸（横屏时尤其重要）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        NetLobby.appContext = applicationContext
        setContent {
            LaoXiangDDZTheme {
                LaoXiangRoot()
            }
        }
    }

    /** 沉浸式全屏：隐藏状态栏 + 导航栏；滑动呼出后自动再隐藏 */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }
}

/** 简单路由（与 Application 类 LaoXiangApp 同名会冲突，故叫 Root） */
private enum class Page { LOBBY, ROOM, GAME, COLLECTION, PDK, BIGTWO, GUANDAN, SHENGJI, MAHJONG }

@Composable
fun LaoXiangRoot() {
    val gameVm: GameViewModel = viewModel()
    val pdkVm: PdkViewModel = viewModel()
    val btVm: BigTwoViewModel = viewModel()
    val gdVm: GuandanViewModel = viewModel()
    val sjVm: ShengjiViewModel = viewModel()
    val mjVm: MjViewModel = viewModel()
    var page by remember { mutableStateOf(Page.LOBBY) }
    // 当前房间页归属："ddz"=斗地主原链路；"net"=NetLobby（跨游戏浏览/非斗地主房间）
    var roomOwner by remember { mutableStateOf("net") }
    val snapshot by gameVm.snapshot.collectAsState()
    val showResult = snapshot?.result != null
    val pdkSnapshot by pdkVm.snapshot.collectAsState()
    val showPdkResult = pdkSnapshot?.result != null

    // NetLobby 偏好注入 + 斗地主手动 IP 转接
    NetLobby.prefsAiLevel = gameVm.prefs.aiLevel
    NetLobby.prefsNickname = gameVm.prefs.nickname
    NetLobby.prefsAvatar = gameVm.prefs.avatar
    NetLobby.onJoinDdz = { ip ->
        roomOwner = "ddz"
        gameVm.startClient(ip)
        page = Page.ROOM
    }

    /** 加入附近房间（按房间游戏路由） */
    fun joinRoom(room: RoomBroadcast) {
        if (room.game == "ddz") {
            NetLobby.leave()
            roomOwner = "ddz"
            gameVm.startClient(room.ip)
        } else {
            roomOwner = "net"
            NetLobby.startClient(room.ip, room.game, room.seats.coerceAtLeast(3))
        }
    }

    /** 房间页 → 对局页（按当前会话游戏路由） */
    fun enterNetGame() {
        if (roomOwner == "ddz") {
            page = Page.GAME
            return
        }
        when (NetLobby.activeGame) {
            "guandan" -> { gdVm.bindNet(); page = Page.GUANDAN }
            "shengji" -> { sjVm.bindNet(); page = Page.SHENGJI }
            "pdk" -> { pdkVm.bindNet(); page = Page.PDK }
            "bigtwo" -> { btVm.bindNet(); page = Page.BIGTWO }
            "mjdazhong", "mjlaizi", "mjsichuan" -> { mjVm.bindNet(); page = Page.MAHJONG }
        }
    }

    when {
        page == Page.PDK && showPdkResult -> PdkResultScreen(
            pdkVm = pdkVm,
            onBackLobby = {
                pdkVm.exitGame()
                page = Page.LOBBY
            },
            onAgain = { pdkVm.again(pdkSnapshot!!.mode) }
        )
        page == Page.PDK -> PdkGameScreen(
            pdkVm = pdkVm,
            onExit = {
                pdkVm.exitGame()
                page = Page.LOBBY
            }
        )
        page == Page.COLLECTION -> CollectionScreen(
            onBack = { page = Page.LOBBY },
            onPlayDdz = {
                gameVm.startSingle(gameVm.prefs.aiLevel)
                page = Page.GAME
            },
            onPlayPdk = { mode ->
                pdkVm.start(mode)
                page = Page.PDK
            },
            onPlayBigTwo = {
                btVm.start()
                page = Page.BIGTWO
            },
            onPlayGuandan = {
                gdVm.start()
                page = Page.GUANDAN
            },
            onPlayShengji = {
                sjVm.start()
                page = Page.SHENGJI
            },
            onPlayMj = { m ->
                mjVm.start(m)
                page = Page.MAHJONG
            }
        )
        page == Page.BIGTWO -> BigTwoGameScreen(
            vm = btVm,
            onExit = {
                btVm.exitGame()
                page = Page.LOBBY
            }
        )
        page == Page.GUANDAN -> GuandanGameScreen(
            vm = gdVm,
            onExit = {
                gdVm.exitGame()
                page = Page.LOBBY
            }
        )
        page == Page.SHENGJI -> ShengjiGameScreen(
            vm = sjVm,
            onExit = {
                sjVm.exitGame()
                page = Page.LOBBY
            }
        )
        page == Page.MAHJONG -> MjGameScreen(
            vm = mjVm,
            onExit = {
                mjVm.exitGame()
                page = Page.LOBBY
            }
        )
        page == Page.GAME && showResult -> ResultScreen(
            gameVm = gameVm,
            onBackLobby = {
                gameVm.leaveGame()
                page = Page.LOBBY
            },
            onAgain = {
                when (gameVm.mode.value) {
                    GameMode.SINGLE -> gameVm.startSingle(gameVm.prefs.aiLevel)
                    GameMode.HOST -> gameVm.hostRestart()
                    GameMode.CLIENT -> page = Page.ROOM   // 客人等待房主再开一局
                }
            }
        )
        page == Page.GAME -> GameScreen(
            gameVm = gameVm,
            onExit = {
                gameVm.leaveGame()
                page = Page.LOBBY
            }
        )
        page == Page.ROOM -> {
            val roomUi: NetRoomUi = if (roomOwner == "ddz") gameVm else NetLobby
            RoomScreen(
                roomUi = roomUi,
                onExit = {
                    roomUi.leaveRoom()
                    page = Page.LOBBY
                },
                onEnterGame = { enterNetGame() },
                onJoinRoom = { joinRoom(it) }
            )
        }
        else -> LobbyScreen(
            gameVm = gameVm,
            onSingle = { level ->
                gameVm.startSingle(level)
                page = Page.GAME
            },
            onHost = { selId ->
                val (game, seats) = parseNetGame(selId)
                if (game == "ddz") {
                    NetLobby.leave()
                    roomOwner = "ddz"
                    gameVm.startHost()
                } else {
                    roomOwner = "net"
                    NetLobby.startHost(game, seats, gameVm.prefs.nickname, gameVm.prefs.avatar)
                }
                page = Page.ROOM
            },
            onJoin = {
                roomOwner = "net"     // 串门浏览页 = NetLobby（跨游戏列表 + 手动 IP 选玩法）
                page = Page.ROOM
            },
            onCollection = { page = Page.COLLECTION }
        )
    }
}
