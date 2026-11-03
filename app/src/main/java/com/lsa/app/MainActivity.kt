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
    /** The screen a notification asked to open (a chat, the connected apps); consumed once the UI navigated there. */
    private val openRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.i("LSA", "MainActivity created")
        openRoute.value = routeOf(intent)
        setContent { AppTheme { AppScaffold(openRoute.collectAsStateWithLifecycle().value) { openRoute.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeOf(intent)?.let { openRoute.value = it }
    }

    private fun routeOf(intent: Intent?): String? = when {
        intent == null -> null
        intent.action == io.github.taehunkim.nunsori.sdk.Nunsori.ACTION_APPROVE -> com.lsa.app.ui.ROUTE_APPS
        else -> intent.getLongExtra(Notifications.EXTRA_SESSION, -1).takeIf { it > 0 }?.let { "chat/$it" }
    }

    override fun onStart() {
        super.onStart()
        // Drop index rows for files deleted while the app was closed, so results never show dead thumbnails.
        lifecycleScope.launch(Dispatchers.IO) { (application as LsaApplication).container.indexing.pruneNow() }
    }
}
