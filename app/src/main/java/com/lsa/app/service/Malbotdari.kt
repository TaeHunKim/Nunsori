package com.lsa.app.service

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** The assistant app Malbotdari, which took over the chat in 0.10.0: open it, or hand it files to ask about. */
object Malbotdari {
    const val PACKAGE = "io.github.taehunkim.malbotdari"
    /** Malbotdari's MainActivity: a new chat with [EXTRA_ITEM_IDS] attached. */
    const val ACTION_NEW_CHAT = "$PACKAGE.action.NEW_CHAT"
    const val EXTRA_ITEM_IDS = "item_ids"
    const val RELEASES_URL = "https://github.com/sonanjip/malbotdari/releases"

    fun isInstalled(ctx: Context): Boolean = runCatching { ctx.packageManager.getPackageInfo(PACKAGE, 0); true }.getOrDefault(false)

    /** Opens Malbotdari (a new chat with [itemIds] attached when given); false when it is not installed. */
    fun open(ctx: Context, itemIds: List<Long> = emptyList()): Boolean {
        val intent = if (itemIds.isEmpty()) ctx.packageManager.getLaunchIntentForPackage(PACKAGE) ?: return false
        else Intent(ACTION_NEW_CHAT).setPackage(PACKAGE).putExtra(EXTRA_ITEM_IDS, itemIds.toLongArray())
        return try { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: ActivityNotFoundException) { false }
    }

    /** Where to get it. */
    fun openReleases(ctx: Context) {
        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
