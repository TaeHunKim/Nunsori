package com.lsa.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lsa.app.ui.AppScaffold
import com.lsa.app.ui.theme.AppTheme

class MainActivity : AppCompatActivity() {
    /** The chat a notification asked to open; consumed by the UI once it navigated there. */
    private val openChat = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.i("LSA", "MainActivity created")
        openChat.value = chatOf(intent)
        setContent { AppTheme { AppScaffold(openChat.collectAsStateWithLifecycle().value) { openChat.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        chatOf(intent)?.let { openChat.value = it }
    }

    private fun chatOf(intent: Intent?): Long? = intent?.getLongExtra(Notifications.EXTRA_SESSION, -1)?.takeIf { it > 0 }

    override fun onStart() {
        super.onStart()
        // Drop index rows for files deleted while the app was closed, so results never show dead thumbnails.
        lifecycleScope.launch(Dispatchers.IO) { (application as LsaApplication).container.indexing.pruneNow() }
    }
}
