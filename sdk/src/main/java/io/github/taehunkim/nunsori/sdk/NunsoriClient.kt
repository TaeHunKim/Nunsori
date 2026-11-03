package io.github.taehunkim.nunsori.sdk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.DeadObjectException
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import io.github.taehunkim.nunsori.INunsori
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A tool Nunsori offers to a chat model; [parameters] is a JSON schema object. [confirm]: the user approves it first. */
data class NunsoriTool(val name: String, val description: String, val parameters: String, val confirm: Boolean)

/** What a confirm tool prepared: shown to the user, then passed to [NunsoriClient.commit]. */
data class Confirmation(val title: String, val summary: String, val files: List<FileRef>, val payload: String)

data class FileRef(val itemId: Long, val name: String, val folder: String)

/** One indexed file as [NunsoriClient.items] reports it. */
data class ItemInfo(
    val itemId: Long, val name: String, val kind: String, val mime: String?, val date: String?, val folder: String, val status: String,
)

/** New files since a cursor, and the cursor to pass next time. */
data class Changes(val ids: List<Long>, val cursor: Long)

/** The outcome of [NunsoriClient.commit]. */
data class CommitResult(val ok: Boolean, val note: String, val error: Int? = null)

/** Versions Nunsori reported in hello. */
data class NunsoriInfo(val apiVersion: Int, val minClientApi: Int, val appVersion: String)

/**
 * Connection to Nunsori's index. Binds on first use, rebinds after Nunsori's process died (calls are retried once,
 * except [commit]), and unbinds after [idleUnbindMs] without calls. All calls are suspend functions on
 * [Dispatchers.IO]; they throw [NunsoriUnavailableException] or [NunsoriCallException].
 */
class NunsoriClient(
    context: Context,
    private val idleUnbindMs: Long = 60_000,
    private val connectTimeoutMs: Long = 15_000,
    private val clientApi: Int = Nunsori.API_VERSION,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var service: INunsori? = null
    @Volatile private var info: NunsoriInfo? = null
    private var connection: ServiceConnection? = null
    private val active = AtomicInteger(0)
    private var unbindJob: Job? = null

    fun isInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(Nunsori.PACKAGE, 0); true
    } catch (_: PackageManager.NameNotFoundException) { false }

    /** Opens Nunsori (null when it is not installed). */
    fun launchIntent(): Intent? = app.packageManager.getLaunchIntentForPackage(Nunsori.PACKAGE)

    suspend fun info(): NunsoriInfo = call { info ?: error("not connected") }

    suspend fun tools(): List<NunsoriTool> = call { s ->
        val arr = JSONArray(s.tools())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            NunsoriTool(o.getString("name"), o.optString("description"), o.optJSONObject("parameters")?.toString() ?: "{}", o.optBoolean("confirm"))
        }
    }

    /** The tool's text result, or a [Confirmation] for a confirm tool. */
    suspend fun runTool(name: String, argsJson: String): Any = call { s ->
        val out = s.runTool(name, argsJson)
        parseConfirmation(out) ?: out
    }

    /** Carries out a confirmed action. Not retried: the first attempt may have run. */
    suspend fun commit(name: String, payload: String): CommitResult = call(retry = false) { s ->
        val o = JSONObject(s.commit(name, payload))
        CommitResult(o.optBoolean("ok"), o.optString("note"), if (o.has("error")) o.optInt("error") else null)
    }

    suspend fun items(ids: List<Long>): List<ItemInfo> = call { s ->
        val arr = JSONArray(s.items(ids.toLongArray()))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            ItemInfo(
                o.getLong("item_id"), o.optString("name"), o.optString("kind"), o.optString("mime").ifEmpty { null },
                o.optString("date").ifEmpty { null }, o.optString("folder"), o.optString("status"),
            )
        }
    }

    /** JPEG bytes, or null when the file has no thumbnail. */
    suspend fun thumbnail(id: Long, maxPx: Int = 256): ByteArray? = call { s ->
        s.thumbnail(id, maxPx)?.let { pfd -> ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() } }
    }

    suspend fun readText(id: Long, maxChars: Int): String = call { it.readText(id, maxChars) }

    /** [cursor] 0 starts from now. [kinds]: document, image, audio, video (empty = all). */
    suspend fun changesSince(cursor: Long, kinds: Collection<String> = emptyList(), limit: Int = 200): Changes = call { s ->
        val o = JSONObject(s.changesSince(cursor, kinds.joinToString(","), limit))
        val arr = o.optJSONArray("ids") ?: JSONArray()
        Changes((0 until arr.length()).map { arr.getLong(it) }, o.optLong("cursor", cursor))
    }

    suspend fun matchItems(query: String, ids: Collection<Long>, kinds: Collection<String> = emptyList()): List<Long> =
        call { it.matchItems(query, ids.toLongArray(), kinds.joinToString(",")).toList() }

    suspend fun people(): List<String> = call { s -> JSONArray(s.people()).let { a -> (0 until a.length()).map { a.getString(it) } } }

    /** Unbinds now. Later calls bind again. */
    fun disconnect() { scope.launch { lock.withLock { disconnectLocked() } } }

    // ---- connection --------------------------------------------------------------------------------------------

    private fun begin() {
        active.incrementAndGet()
        synchronized(this) { unbindJob?.cancel(); unbindJob = null }
    }

    private fun end() {
        if (active.decrementAndGet() > 0) return
        synchronized(this) {
            unbindJob?.cancel()
            unbindJob = scope.launch {
                delay(idleUnbindMs)
                lock.withLock { if (active.get() == 0) disconnectLocked() }
            }
        }
    }

    private suspend fun <T> call(retry: Boolean = true, minApi: Int = Nunsori.Api.V1, block: (INunsori) -> T): T = withContext(Dispatchers.IO) {
        begin()
        try {
            var attempt = 0
            while (true) {
                val s = connect()
                val api = info?.apiVersion ?: 0
                if (api < minApi) throw NunsoriUnavailableException(NunsoriUnavailableException.Reason.HOST_TOO_OLD, "needs Nunsori API $minApi, it has $api")
                try {
                    return@withContext block(s)
                } catch (e: DeadObjectException) {
                    lock.withLock { if (service === s) disconnectLocked() }
                    if (!retry || attempt++ > 0) throw NunsoriUnavailableException(NunsoriUnavailableException.Reason.NO_ANSWER, "Nunsori died", e)
                    Log.w(TAG, "Nunsori died, reconnecting")
                } catch (e: IllegalStateException) {
                    throw NunsoriErrors.decode(e) ?: e
                } catch (e: SecurityException) {
                    throw NunsoriErrors.refusal(e)
                }
            }
            @Suppress("UNREACHABLE_CODE") error("unreachable")
        } finally { end() }
    }

    private suspend fun connect(): INunsori = lock.withLock {
        service?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
        disconnectLocked()
        if (!isInstalled()) throw NunsoriUnavailableException(NunsoriUnavailableException.Reason.NOT_INSTALLED)
        val s = withTimeoutOrNull(connectTimeoutMs) { bind() }
            ?: run { disconnectLocked(); throw NunsoriUnavailableException(NunsoriUnavailableException.Reason.NO_ANSWER, "bind timeout") }
        try {
            val o = JSONObject(s.hello(JSONObject().put("api", clientApi).put("sdk", Nunsori.SDK_VERSION).toString()))
            info = NunsoriInfo(o.optInt("api"), o.optInt("minClientApi"), o.optString("appVersion"))
        } catch (e: IllegalStateException) {
            disconnectLocked()
            val err = NunsoriErrors.decode(e) ?: throw e
            if (err.code == Nunsori.Errors.CLIENT_TOO_OLD) throw NunsoriUnavailableException(NunsoriUnavailableException.Reason.CLIENT_TOO_OLD, err.message)
            throw err
        } catch (e: SecurityException) {
            disconnectLocked(); throw NunsoriErrors.refusal(e)
        } catch (e: DeadObjectException) {
            disconnectLocked(); throw NunsoriUnavailableException(NunsoriUnavailableException.Reason.NO_ANSWER, "Nunsori died", e)
        }
        service = s
        s
    }

    private suspend fun bind(): INunsori = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                if (cont.isActive) cont.resume(INunsori.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
            override fun onBindingDied(name: ComponentName?) {
                service = null
                if (cont.isActive) cont.resumeWithException(NunsoriUnavailableException(NunsoriUnavailableException.Reason.NO_ANSWER, "binding died"))
                else {
                    val self = this
                    scope.launch { lock.withLock { if (connection === self) disconnectLocked() } }
                }
            }
            override fun onNullBinding(name: ComponentName?) {
                if (cont.isActive) cont.resumeWithException(NunsoriUnavailableException(NunsoriUnavailableException.Reason.NOT_ALLOWED, "null binding"))
            }
        }
        connection = conn
        val intent = Intent(Nunsori.ACTION_BIND).setPackage(Nunsori.PACKAGE)
        val ok = try { app.bindService(intent, conn, Context.BIND_AUTO_CREATE) } catch (_: SecurityException) { false }
        if (!ok) {
            runCatching { app.unbindService(conn) }
            connection = null
            if (cont.isActive) cont.resumeWithException(NunsoriUnavailableException(NunsoriUnavailableException.Reason.NOT_ALLOWED, "bindService failed (Nunsori too old?)"))
        }
    }

    private fun disconnectLocked() {
        service = null
        info = null
        connection?.let { runCatching { app.unbindService(it) } }
        connection = null
    }

    companion object {
        private const val TAG = "Nunsori.Client"

        /** A confirm tool's answer, or null for a plain text result. */
        fun parseConfirmation(out: String): Confirmation? {
            if (!out.trimStart().startsWith("{")) return null
            val c = runCatching { JSONObject(out).optJSONObject("confirm") }.getOrNull() ?: return null
            val files = c.optJSONArray("files") ?: JSONArray()
            return Confirmation(
                c.optString("title"), c.optString("summary"),
                (0 until files.length()).map { files.getJSONObject(it).let { f -> FileRef(f.optLong("id"), f.optString("name"), f.optString("folder")) } },
                c.optJSONObject("payload")?.toString() ?: "{}",
            )
        }
    }
}
