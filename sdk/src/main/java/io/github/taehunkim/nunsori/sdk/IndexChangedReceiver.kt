package io.github.taehunkim.nunsori.sdk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Declared by the SDK's manifest, so every client app receives [Nunsori.ACTION_INDEX_CHANGED] even when it is not
 * running: the process starts, `Application.onCreate` sets [handler], then the broadcast is passed on. It carries
 * no data and anyone can send it: the handler asks Nunsori ([NunsoriClient.changesSince]).
 */
class IndexChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Nunsori.ACTION_INDEX_CHANGED) return
        handler?.invoke()
    }

    companion object {
        /** Called on the main thread. Keep it short: schedule work. */
        @Volatile var handler: (() -> Unit)? = null
    }
}
