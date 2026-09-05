package com.laoxiang.ddz.net

/**
 * 出站消息队列（v19 联机网络优化核心）
 *
 * 背景：此前发送端在调用线程上直接同步写 socket，存在两类严重问题：
 *  1. 客户端的 bid/play/pass/chat 全部从 UI 主线程调用 → Android 主线程
 *     禁止网络 IO，抛出的 NetworkOnMainThreadException 被空 catch 静默吞掉，
 *     消息根本没发出去，用户看到的就是「点了没反应→几秒后提示送达失败」；
 *  2. 房主在游戏逻辑线程上逐个客户端同步写 → 一个慢/假死的客户端会
 *     拖住整个牌局（房主和另一个客人的所有操作都跟着卡住）。
 *
 * 现在所有发送只是「入队」——永不阻塞调用线程、永不抛网络异常；
 * 每条连接配一个专职写协程在 IO 线程排队写出。
 * 对局快照在队内可合并（保留最新状态、累积特效），慢客户端再多广播也不堆积。
 */
internal class OutboundQueue {

    /** 显式用 java.lang.Object：Kotlin 的 Any 不暴露 wait/notifyAll */
    private val lock = java.lang.Object()
    private val queue = ArrayDeque<NetMsg>()
    private var closed = false

    /** 入队（非阻塞）。返回 false = 队列已关闭或过载丢弃 */
    fun offer(msg: NetMsg): Boolean {
        synchronized(lock) {
            if (closed) return false
            if (queue.size >= MAX_PENDING) return false   // 过载保护：写不动时丢弃，写线程失败会断开连接
            if (msg is NetMsg.Snapshot) {
                // 队列里已有未发出去的快照 → 合并：状态取最新，特效按序累积不丢
                val idx = queue.indexOfFirst { it is NetMsg.Snapshot }
                if (idx >= 0) {
                    val old = queue.removeAt(idx) as NetMsg.Snapshot
                    queue.add(idx, old.copy(snapshot = msg.snapshot, effects = old.effects + msg.effects))
                    lock.notifyAll()
                    return true
                }
            }
            queue.addLast(msg)
            lock.notifyAll()
            return true
        }
    }

    /** 取消息（阻塞直到有消息或关闭）；返回 null = 队列已关闭，写协程应退出 */
    fun take(): NetMsg? {
        synchronized(lock) {
            while (true) {
                if (queue.isNotEmpty()) return queue.removeFirst()
                if (closed) return null
                lock.wait()
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    companion object {
        private const val MAX_PENDING = 256
    }
}
