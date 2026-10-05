package com.myhealth.data.repository

/**
 * Runs a block inside one database transaction. An interface rather than a direct
 * `MyHealthDatabase` reference so `ActivityIngestor` can be unit-tested against fake DAOs with no
 * Room and no Android (P2.5).
 */
interface TransactionRunner {
    suspend operator fun <T> invoke(block: suspend () -> T): T
}

/** For tests and for callers that are already inside a transaction. */
object DirectTransactionRunner : TransactionRunner {
    override suspend fun <T> invoke(block: suspend () -> T): T = block()
}
