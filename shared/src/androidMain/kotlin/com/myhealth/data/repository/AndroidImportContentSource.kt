package com.myhealth.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okio.Source
import okio.source

/** `content://` documents handed over by `OpenDocument` or the share sheet (P7.6). */
class AndroidImportContentSource(private val context: Context) : ImportContentSource {

    override suspend fun displayName(uri: String): String {
        val parsed = Uri.parse(uri)
        context.contentResolver
            .query(parsed, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getString(0)
            }
        return parsed.lastPathSegment?.substringAfterLast('/') ?: uri
    }

    override suspend fun openSource(uri: String): Source =
        checkNotNull(context.contentResolver.openInputStream(Uri.parse(uri))) {
            "Could not open $uri"
        }.source()
}
