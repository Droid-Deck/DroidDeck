package com.droiddeck.launcher.stores.gfn

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import org.json.JSONObject

/**
 * Hosts the one OpenNOW core in the :gfn process (the core locks its data directory, so a second
 * copy cannot run) and answers core requests from the Stores page in the main process.
 */
class GfnCoreService : Service() {
    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != REQUEST) return super.onTransact(code, data, reply, flags)
            if (getCallingUid() != Process.myUid() || reply == null) return false
            val method = data.readString().orEmpty()
            val params = runCatching { JSONObject(data.readString() ?: "{}") }.getOrDefault(JSONObject())
            val timeoutMs = data.readLong()
            try {
                val result = GfnCore.request(applicationContext, method, params, timeoutMs)
                reply.writeInt(1)
                reply.writeString(result.toString())
            } catch (e: Exception) {
                reply.writeInt(0)
                reply.writeString(JSONObject().put("code", (e as? GfnError)?.code ?: "error")
                    .put("message", e.message ?: "GeForce NOW request failed ($method)").toString())
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    companion object {
        const val REQUEST = IBinder.FIRST_CALL_TRANSACTION
    }
}

/** The main process's side of [GfnCoreService]. Blocking: call off the main thread. */
internal object GfnRemote {
    private val lock = Object()
    @Volatile private var binder: IBinder? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            synchronized(lock) { binder = service; lock.notifyAll() }
        }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
        override fun onBindingDied(name: ComponentName?) { synchronized(lock) { binder = null; bound = false } }
    }

    fun request(context: Context, method: String, params: JSONObject, timeoutMs: Long): JSONObject {
        val service = connect(context)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeString(method)
            data.writeString(params.toString())
            data.writeLong(timeoutMs)
            if (!service.transact(GfnCoreService.REQUEST, data, reply, 0))
                throw GfnError("core_stopped", "The GeForce NOW service refused the request")
            val ok = reply.readInt() == 1
            val body = reply.readString() ?: "{}"
            if (ok) return JSONObject(body)
            val error = JSONObject(body)
            throw GfnError(error.optString("code").ifEmpty { "error" }, error.optString("message"))
        } catch (e: RemoteException) {
            binder = null
            throw GfnError("core_stopped", "The GeForce NOW service stopped")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun connect(context: Context): IBinder {
        synchronized(lock) {
            binder?.takeIf { it.isBinderAlive }?.let { return it }
            val app = context.applicationContext
            if (!bound) bound = app.bindService(Intent(app, GfnCoreService::class.java), connection, Context.BIND_AUTO_CREATE)
            if (!bound) throw GfnError("core_stopped", "The GeForce NOW service could not start")
            val deadline = System.currentTimeMillis() + 15_000
            while (binder?.isBinderAlive != true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) throw GfnError("timeout", "The GeForce NOW service did not start")
                lock.wait(left)
            }
            return binder!!
        }
    }
}
