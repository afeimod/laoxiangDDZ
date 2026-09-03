package com.laoxiang.ddz

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.viewmodel.compose.viewModel
import com.laoxiang.ddz.ui.game.GameMode
import com.laoxiang.ddz.ui.game.GameScreen
import com.laoxiang.ddz.ui.game.GameViewModel
import com.laoxiang.ddz.ui.lobby.LobbyScreen
import com.laoxiang.ddz.ui.result.ResultScreen
import com.laoxiang.ddz.ui.room.RoomScreen
import com.laoxiang.ddz.ui.theme.LaoXiangDDZTheme

/**
 * 老乡斗地主 主界面
 * 页面流：大厅 → （房间 | 对局） → 结算 → 返回
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LaoXiangDDZTheme {
                LaoXiangApp()
            }
        }
    }
}

/** 简单路由 */
private enum class Page { LOBBY, ROOM, GAME }

@Composable
fun LaoXiangApp() {
    val gameVm: GameViewModel = viewModel()
    var page by remember { mutableStateOf(Page.LOBBY) }
    val snapshot by gameVm.snapshot.collectAsState()
    val showResult = snapshot?.result != null

    when {
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
        page == Page.ROOM -> RoomScreen(
            gameVm = gameVm,
            onExit = {
                gameVm.leaveGame()
                page = Page.LOBBY
            },
            onEnterGame = { page = Page.GAME }
        )
        else -> LobbyScreen(
            gameVm = gameVm,
            onSingle = { level ->
                gameVm.startSingle(level)
                page = Page.GAME
            },
            onHost = {
                gameVm.startHost()
                page = Page.ROOM
            },
            onJoin = { page = Page.ROOM }
        )
    }
}
