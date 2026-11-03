package com.lsa.app.service

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import com.lsa.app.LsaApplication
import com.lsa.app.ui.common.openFile
import io.github.taehunkim.nunsori.sdk.Nunsori
import kotlinx.coroutines.runBlocking

/**
 * Opens one indexed file in the phone's viewer for another app (Nunsori.openItemIntent), then closes. Other apps
 * never get the file itself: Nunsori hands it to the viewer the user picks.
 */
class OpenItemActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.getLongExtra(Nunsori.EXTRA_ITEM_ID, -1) ?: -1
        val item = if (id > 0) runBlocking { (application as LsaApplication).container.db.itemDao().summaries(listOf(id)).firstOrNull() } else null
        if (item != null) openFile(this, Uri.parse(item.uri), item.displayName)
        finish()
    }
}
