package com.droiddeck.launcher.stores.xbox

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Hands the main process's Xbox Cloud Gaming tokens to the native player's process (:xcloudnative) over Binder, so
 * they stay in memory (not exported, same app only) and a stream does not wait for a second ~20 s token fetch.
 */
class XboxCloudTokenService : Service() {
    private val worker = Executors.newSingleThreadExecutor()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val replyTo = message.replyTo ?: return@Handler true
        val account = message.data?.getString("account").orEmpty()
        worker.execute {
            val reply = Message.obtain(null, REPLY)
            reply.data = runCatching { XboxCloudSession.toBundle(XboxCloudSession.get(applicationContext, account)) }
                .getOrElse { Bundle().apply { putString("error", it.message ?: it.javaClass.simpleName) } }
            runCatching { replyTo.send(reply) }
        }
        true
    })

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    companion object {
        private const val REQUEST = 1
        private const val REPLY = 2

        /** Blocks until the main process answers (its tokens, or "error"), or returns null when it cannot be reached. */
        fun request(context: Context, account: String, timeoutSeconds: Long): Bundle? {
            val app = context.applicationContext
            val done = CountDownLatch(1)
            var answer: Bundle? = null
            val thread = HandlerThread("xcloud-token").apply { start() }
            val replies = Messenger(Handler(thread.looper) { message ->
                if (message.what == REPLY) { answer = message.data; done.countDown() }
                true
            })
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val ask = Message.obtain(null, REQUEST).apply { data = Bundle().apply { putString("account", account) }; replyTo = replies }
                    if (binder == null || runCatching { Messenger(binder).send(ask) }.isFailure) done.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName?) { done.countDown() }
            }
            val bound = runCatching { app.bindService(Intent(app, XboxCloudTokenService::class.java), connection, Context.BIND_AUTO_CREATE) }
                .getOrDefault(false)
            try {
                if (!bound) return null
                done.await(timeoutSeconds, TimeUnit.SECONDS)
                return answer
            } finally {
                if (bound) runCatching { app.unbindService(connection) }
                thread.quitSafely()
            }
        }
    }
}
