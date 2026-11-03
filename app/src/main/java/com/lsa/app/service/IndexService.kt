package com.lsa.app.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.lsa.app.LsaApplication
import io.github.taehunkim.nunsori.INunsori
import io.github.taehunkim.nunsori.sdk.Nunsori

/** The bound service other apps use (action [Nunsori.ACTION_BIND]). Every call checks the caller first ([CallerCheck]). */
class IndexService : Service() {
    private val binder by lazy {
        val c = (application as LsaApplication).container
        val api = c.indexApi
        val check = c.callerCheck
        object : INunsori.Stub() {
            override fun apiVersion(): Int = Nunsori.API_VERSION
            override fun hello(clientJson: String): String { check.require(); return api.hello(clientJson) }
            override fun tools(): String { check.require(); return api.tools() }
            override fun runTool(name: String, argsJson: String): String = logged("runTool $name") { check.require(); api.runTool(name, argsJson) }
            override fun commit(name: String, payloadJson: String): String = logged("commit $name") { check.require(); api.commit(name, payloadJson) }
            override fun items(ids: LongArray): String { check.require(); return api.items(ids) }
            override fun thumbnail(id: Long, maxPx: Int) = run { check.require(); api.thumbnailBytes(id, maxPx)?.let(IndexApi::pipeOf) }
            override fun readText(id: Long, maxChars: Int): String { check.require(); return api.readText(id, maxChars) }
            override fun changesSince(cursor: Long, kinds: String, limit: Int): String { check.require(); return api.changesSince(cursor, kinds, limit) }
            override fun matchItems(query: String, ids: LongArray, kinds: String): LongArray { check.require(); return api.matchItems(query, ids, kinds) }
            override fun people(): String { check.require(); return api.people() }
        }
    }

    private inline fun <T> logged(what: String, block: () -> T): T = try { block() } catch (e: RuntimeException) {
        if (e !is SecurityException && e !is IllegalStateException) Log.w(TAG, "$what failed", e)
        throw e
    }

    override fun onBind(intent: Intent): IBinder? = if (intent.action == Nunsori.ACTION_BIND) binder else null

    private companion object { const val TAG = "LSA.IndexService" }
}
