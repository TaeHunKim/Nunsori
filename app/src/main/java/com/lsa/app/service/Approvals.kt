package com.lsa.app.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** The user's decision about an app that is not signed with one of the Nunsori's own key. */
data class Approval(
    val pkg: String,
    /** SHA-256 of the signing certificate the decision was made for (hex, lower case). */
    val certSha256: String,
    val state: State,
    /** When the app last asked (millis). */
    val requestedAt: Long,
) {
    enum class State { PENDING, ALLOWED, DENIED }
}

/**
 * Third-party apps the user allowed or denied, keyed by package. A decision holds only for the certificate it was
 * made for: an app re-signed with another key asks again. Kept in memory for the Binder threads and persisted in
 * the settings as JSON.
 */
class Approvals(private val load: suspend () -> String, private val save: suspend (String) -> Unit, private val scope: CoroutineScope) {
    private val _all = MutableStateFlow<Map<String, Approval>>(emptyMap())
    val all: StateFlow<Map<String, Approval>> = _all.asStateFlow()
    @Volatile private var loaded = false
    private val lock = Any()

    /** Called whenever a decision changes (the caller check drops its cache). */
    var onChange: () -> Unit = {}

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            _all.value = decode(runBlocking { load() })
            loaded = true
        }
    }

    fun get(pkg: String): Approval? { ensureLoaded(); return _all.value[pkg] }

    /** Records a request from an unknown (or re-signed) app; returns true when it is new, so the user is told. */
    fun request(pkg: String, cert: String, now: Long = System.currentTimeMillis()): Boolean {
        ensureLoaded()
        synchronized(lock) {
            val old = _all.value[pkg]
            if (old != null && old.certSha256 == cert && old.state == Approval.State.PENDING) return false
            put(Approval(pkg, cert, Approval.State.PENDING, now))
            return true
        }
    }

    fun decide(pkg: String, allow: Boolean) {
        ensureLoaded()
        synchronized(lock) {
            val old = _all.value[pkg] ?: return
            put(old.copy(state = if (allow) Approval.State.ALLOWED else Approval.State.DENIED))
        }
    }

    fun forget(pkg: String) {
        ensureLoaded()
        synchronized(lock) {
            _all.value = _all.value - pkg
            persist()
        }
    }

    private fun put(a: Approval) {
        _all.value = _all.value + (a.pkg to a)
        persist()
    }

    private fun persist() {
        val json = encode(_all.value)
        scope.launch { save(json) }
        onChange()
    }

    companion object {
        fun encode(all: Map<String, Approval>): String = JSONObject().apply {
            all.values.forEach { a ->
                put(a.pkg, JSONObject().put("cert", a.certSha256).put("state", a.state.name).put("at", a.requestedAt))
            }
        }.toString()

        fun decode(json: String): Map<String, Approval> {
            if (json.isBlank()) return emptyMap()
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            return o.keys().asSequence().mapNotNull { pkg ->
                val e = o.optJSONObject(pkg) ?: return@mapNotNull null
                val state = runCatching { Approval.State.valueOf(e.optString("state")) }.getOrNull() ?: return@mapNotNull null
                pkg to Approval(pkg, e.optString("cert"), state, e.optLong("at"))
            }.toMap()
        }
    }
}
