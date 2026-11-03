package io.github.taehunkim.nunsori.sdk

import android.content.Intent

/** Constants shared by Nunsori and its client apps. */
object Nunsori {
    /** Package name of the Nunsori app. */
    const val PACKAGE = "io.github.taehunkim.nunsori"

    /** Intent action of Nunsori's bound service. */
    const val ACTION_BIND = "$PACKAGE.action.BIND"

    /**
     * Sent (explicitly, per package) to every app with a receiver for it after an indexing pass added or changed
     * files. It carries no data: anyone could send it, so receivers only treat it as a hint to call
     * [NunsoriClient.changesSince]. The SDK's [IndexChangedReceiver] passes it on.
     */
    const val ACTION_INDEX_CHANGED = "$PACKAGE.action.INDEX_CHANGED"

    /** Opens one indexed file in Nunsori's viewer. Extra [EXTRA_ITEM_ID]. */
    const val ACTION_OPEN_ITEM = "$PACKAGE.action.OPEN_ITEM"
    const val EXTRA_ITEM_ID = "item_id"

    /** Opens the list of apps waiting for approval in Nunsori. */
    const val ACTION_APPROVE = "$PACKAGE.action.APPROVE"

    /** API level of this SDK. Bump it whenever a method or JSON field is added, and note the level on it ([Api]). */
    const val API_VERSION = Api.V1

    val SDK_VERSION: String get() = BuildConfig.SDK_VERSION

    object Api {
        /** hello, tools, runTool, commit, items, thumbnail, readText, changesSince, matchItems, people. */
        const val V1 = 1
    }

    /** Error codes of [NunsoriCallException] (sent over Binder by [NunsoriErrors]). */
    object Errors {
        /** Input rejected (unknown tool, bad JSON, ...). */
        const val BAD_INPUT = 3
        /** The client's API level is below Nunsori's minimum: the client app must be updated. */
        const val CLIENT_TOO_OLD = 5
        /** The user has not allowed this app in Nunsori (yet). Open [approvalIntent]. Sent inside a SecurityException. */
        const val NOT_APPROVED = 6
        /** Moving or deleting files needs Nunsori's "All files access", which is off. Open Nunsori to grant it. */
        const val NEEDS_FILE_ACCESS = 7
    }

    /** Opens [itemId] in Nunsori (in Nunsori's own task). */
    fun openItemIntent(itemId: Long): Intent =
        Intent(ACTION_OPEN_ITEM).setPackage(PACKAGE).putExtra(EXTRA_ITEM_ID, itemId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens the approval list in Nunsori, in Nunsori's own task. */
    fun approvalIntent(): Intent = Intent(ACTION_APPROVE).setPackage(PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
