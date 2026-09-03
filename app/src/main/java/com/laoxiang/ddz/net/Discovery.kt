package com.laoxiang.ddz.net

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 局域网房间发现
 *
 * 房主端 [RoomAdvertiser]：UDP 广播房间信息（默认每 2 秒）
 * 客户端 [RoomScanner]：监听广播，聚合去重，实时回调房间列表
 */

@Serializable
data class RoomBroadcast(
    val app: String = "LaoXiangDDZ",
    val roomName: String,
    val hostName: String,
    val ip: String,
    val port: Int = NetPorts.GAME,
    val players: Int,
    val started: Boolean
)

/** 房主广播器 */
class RoomAdvertiser(
    private val roomName: String,
    private val hostName: String,
    private val scope: CoroutineScope
) {
    private var job: Job? = null
    private var socket: DatagramSocket? = null

    @Volatile
    var playerCount: Int = 1

    @Volatile
    var started: Boolean = false

    fun start() {
        stop()
        job = scope.launch(Dispatchers.IO) {
            try {
                socket = DatagramSocket().apply {
                    broadcast = true
                    reuseAddress = true
                }
                val bcAddr = InetAddress.getByName(NetUtils.broadcastAddress())
                while (isActive) {
                    try {
                        val msg = RoomBroadcast(
                            roomName = roomName,
                            hostName = hostName,
                            ip = NetUtils.localIpAddress() ?: "?",
                            players = playerCount,
                            started = started
                        )
                        val data = netJson.encodeToString(msg).toByteArray(Charsets.UTF_8)
                        socket?.send(DatagramPacket(data, data.size, bcAddr, NetPorts.DISCOVERY))
                    } catch (_: Exception) {
                    }
                    delay(2000)
                }
            } catch (_: Exception) {
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { socket?.close() }
        socket = null
    }
}

/** 客户端扫描器 */
class RoomScanner(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onRooms: (List<RoomBroadcast>) -> Unit
) {
    private var job: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val found = LinkedHashMap<String, RoomBroadcast>()

    fun start() {
        stop()
        found.clear()
        // 部分设备需 MulticastLock 才能收 UDP 广播
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("ddz_scan")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        job = scope.launch(Dispatchers.IO) {
            val socket = try {
                DatagramSocket(NetPorts.DISCOVERY).apply {
                    broadcast = true
                    reuseAddress = true
                    soTimeout = 1500
                }
            } catch (e: Exception) {
                return@launch
            }
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            while (isActive) {
                try {
                    packet.length = buf.size
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val room = netJson.decodeFromString(RoomBroadcast.serializer(), text)
                    if (room.app == "LaoXiangDDZ") {
                        found[room.ip] = room
                        // 12 秒未见广播的房间剔除
                        // （简单实现：收到即刷新；UI 层另外定期过期）
                        onRooms(found.values.toList())
                    }
                } catch (_: java.net.SocketTimeoutException) {
                    // 超时继续循环
                } catch (_: Exception) {
                }
            }
            runCatching { socket.close() }
        }
    }

    /** 手动直连也走同一数据结构 */
    fun addManual(room: RoomBroadcast) {
        found[room.ip] = room
        onRooms(found.values.toList())
    }

    fun stop() {
        job?.cancel()
        job = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }
}
