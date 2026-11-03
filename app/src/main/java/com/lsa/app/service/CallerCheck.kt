package com.lsa.app.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Process
import io.github.taehunkim.nunsori.sdk.Nunsori
import io.github.taehunkim.nunsori.sdk.NunsoriErrors
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Who may use the index (same rules as OndolSaem's host). Apps signed with Nunsori's own key always may (the
 * companion apps); any other app needs the user's approval ([Approvals]): its first call records a request, tells the
 * user ([onRequest]) and is refused with [Nunsori.Errors.NOT_APPROVED] until the user allows it. Allowed callers are
 * cached per uid until a decision changes.
 */
class CallerCheck(private val context: Context, private val approvals: Approvals) {
    private val cache = ConcurrentHashMap<Int, String>()

    /** A new approval request was recorded for this package. */
    var onRequest: (pkg: String) -> Unit = {}

    init { approvals.onChange = { cache.clear() } }

    /** The calling package; otherwise throws [SecurityException] carrying [Nunsori.Errors.NOT_APPROVED]. */
    fun require(): String {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return context.packageName
        cache[uid]?.let { return it }
        val token = Binder.clearCallingIdentity()
        try {
            val pm = context.packageManager
            val pkgs = pm.getPackagesForUid(uid).orEmpty()
            pkgs.firstOrNull { pm.checkSignatures(context.packageName, it) == PackageManager.SIGNATURE_MATCH }?.let { cache[uid] = it; return it }
            val pkg = pkgs.minOrNull() ?: throw refused("uid $uid has no package")
            val cert = certSha256(pkg) ?: throw refused("$pkg has no signing certificate")
            when (decide(approvals.get(pkg), cert)) {
                Decision.ALLOW -> { cache[uid] = pkg; return pkg }
                Decision.DENY -> throw refused("$pkg was denied access to Nunsori")
                Decision.ASK -> {
                    if (approvals.request(pkg, cert)) onRequest(pkg)
                    throw refused("$pkg is waiting for approval in Nunsori")
                }
                Decision.WAIT -> throw refused("$pkg is waiting for approval in Nunsori")
            }
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun refused(message: String) = SecurityException(NunsoriErrors.encode(Nunsori.Errors.NOT_APPROVED, message).message)

    private fun certSha256(pkg: String): String? = runCatching {
        val info = context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        signers.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.sorted().joinToString(",")
    }.getOrNull()

    enum class Decision { ALLOW, DENY, ASK, WAIT }

    companion object {
        fun decide(stored: Approval?, cert: String): Decision = when {
            stored == null || stored.certSha256 != cert -> Decision.ASK // unknown, or re-signed with another key
            stored.state == Approval.State.ALLOWED -> Decision.ALLOW
            stored.state == Approval.State.DENIED -> Decision.DENY
            else -> Decision.WAIT
        }
    }
}
