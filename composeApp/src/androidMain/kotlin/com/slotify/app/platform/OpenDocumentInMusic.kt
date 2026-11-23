package com.slotify.app.platform

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/**
 * `ACTION_OPEN_DOCUMENT` with `EXTRA_INITIAL_URI` so the picker opens **inside
 * the Music tree** (or the last granted tree) instead of Recents.
 * Input: MIME types. Output: document URI or null.
 */
class OpenDocumentInMusic : ActivityResultContract<Array<String>, Uri?>() {

    override fun createIntent(context: Context, input: Array<String>): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, input)
            val initial = SafMusic.savedTree() ?: SafMusic.musicTreeHint()
            putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
