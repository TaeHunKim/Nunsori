package com.lsa.app

import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.lsa.app.ui.AppScaffold
import com.lsa.app.ui.theme.AppTheme

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.i("LSA", "MainActivity created")
        setContent { AppTheme { AppScaffold() } }
    }

    override fun onStart() {
        super.onStart()
        // Drop index rows for files deleted while the app was closed, so results never show dead thumbnails.
        lifecycleScope.launch(Dispatchers.IO) { (application as LsaApplication).container.indexing.pruneNow() }
    }
}
