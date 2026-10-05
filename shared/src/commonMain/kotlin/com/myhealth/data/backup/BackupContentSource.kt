package com.myhealth.data.backup

import okio.Sink
import okio.Source

/** The document-provider side of a backup, so the service itself stays free of platform types. */
interface BackupContentSource {
    suspend fun openInput(uri: String): Source

    suspend fun openOutput(uri: String): Sink
}
