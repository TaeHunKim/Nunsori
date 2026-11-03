package com.lsa.app.service

import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.taehunkim.nunsori.sdk.Nunsori

/**
 * Tells other apps that the index changed: an explicit broadcast (no data) to every app with a receiver for
 * [Nunsori.ACTION_INDEX_CHANGED], so it reaches apps that are not running. They ask the service what is new, which
 * checks who they are, so it does not matter who receives the hint.
 */
object IndexEvents {
    fun notifyChanged(context: Context) {
        val probe = Intent(Nunsori.ACTION_INDEX_CHANGED)
        @Suppress("DEPRECATION") // the flags overload is API 33+
        val pkgs = context.packageManager.queryBroadcastReceivers(probe, 0)
            .map { it.activityInfo.packageName }.filter { it != context.packageName }.distinct()
        for (pkg in pkgs) {
            runCatching {
                context.sendBroadcast(Intent(Nunsori.ACTION_INDEX_CHANGED).setPackage(pkg).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
            }.onFailure { Log.w(TAG, "broadcast to $pkg failed", it) }
        }
        if (pkgs.isNotEmpty()) Log.i(TAG, "index changed: told ${pkgs.joinToString()}")
    }

    private const val TAG = "LSA.IndexEvents"
}
