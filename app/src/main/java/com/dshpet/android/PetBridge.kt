package com.dshpet.android

import android.os.Handler
import android.os.HandlerThread
import org.json.JSONObject
import java.io.File

/**
 * 桌宠 ↔ 外部程序 的文件桥。
 *
 * 为什么用文件而不是广播/HTTP：
 *  - 广播需要 `am broadcast`，本机策略禁用；
 *  - HTTP 需要 app 和调用方共享 127.0.0.1，本机不成立；
 *  - 公共下载目录两边都能访问（app 需 MANAGE_EXTERNAL_STORAGE），最稳。
 *
 * 协议（都放在 /sdcard/Download/.dshpet/）：
 *   cmd.json    ← 外部写入（机器人），宠物每秒轮询一次
 *               {"action":"say","text":"..."}           只说话
 *               {"action":"play","id":"a027"}           只播动作
 *               {"action":"say_play","id":"a027","text":"..."} 先播动作再说话
 *   state.json  → 宠物回写（心跳/是否在桌面上），外部据此判断桌宠是否活着
 *
 * 注意：cmd.json 内容不变就不会重复触发；改一个字才会再执行。
 */
object PetBridge {

    private const val DIR = "/sdcard/Download/.dshpet"
    private var thread: HandlerThread? = null
    @Volatile private var lastKey: String = ""
    @Volatile private var lastStateAt: Long = 0L

    fun start(svc: PetService) {
        if (thread != null) return
        try {
            File(DIR).mkdirs()
        } catch (_: Throwable) {
        }
        val t = HandlerThread("PetBridge").also { it.start() }
        thread = t
        val h = Handler(t.looper)
        h.post(object : Runnable {
            override fun run() {
                try {
                    poll(svc)
                } catch (_: Throwable) {
                }
                h.postDelayed(this, 1000L)
            }
        })
    }

    private fun poll(svc: PetService) {
        val cmd = File(DIR, "cmd.json")
        val now = System.currentTimeMillis()
        if (cmd.exists()) {
            val text = try {
                cmd.readText()
            } catch (_: Throwable) {
                ""
            }
            if (text.isNotBlank()) {
                val key = Integer.toHexString(text.hashCode()) + ":" + text.length
                if (key != lastKey) {
                    lastKey = key
                    dispatch(svc, text)
                }
            }
        }
        if (now - lastStateAt > 5000L) {
            lastStateAt = now
            writeState(svc)
        }
    }

    private fun dispatch(svc: PetService, text: String) {
        val o = try {
            JSONObject(text)
        } catch (_: Throwable) {
            return
        }
        val action = o.optString("action", "say")
        val say = o.optString("text", "")
        val id = o.optString("id", "")
        when (action) {
            "say" -> if (say.isNotEmpty()) svc.bridgeSay(say)
            "play" -> if (id.isNotEmpty()) svc.bridgePlay(id)
            "say_play" -> {
                if (id.isNotEmpty()) svc.bridgePlay(id)
                if (say.isNotEmpty()) svc.bridgeSay(say)
            }
            "screen" -> svc.bridgeScreen()
            else -> Unit
        }
    }

    private fun writeState(svc: PetService) {
        try {
            val o = JSONObject()
            o.put("ts", System.currentTimeMillis())
            o.put("pets", svc.bridgePetCount())
            o.put("lastKey", lastKey)
            val f = File(DIR, "state.json")
            val tmp = File(DIR, "state.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(f)
        } catch (_: Throwable) {
        }
    }
}
