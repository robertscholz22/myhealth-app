package com.myhealth.seeder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "HcSeeder"

/**
 * adb shell am broadcast -a com.myhealth.seeder.SEED -p com.myhealth.seeder --ei days 45
 * adb shell am broadcast -a com.myhealth.seeder.CLEAR -p com.myhealth.seeder
 */
class SeedReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        when (intent.action) {
            "com.myhealth.seeder.SEED" -> {
                val days = intent.getIntExtra("days", 45)
                val clear = intent.getBooleanExtra("clear", false)
                val seed = intent.getLongExtra("seed", 42L)
                scope.launch {
                    try {
                        val seeder = Seeder(appContext)
                        if (clear) {
                            Log.i(TAG, "SEED: clearing existing seeder data first")
                            seeder.clear()
                        }
                        Log.i(TAG, "SEED: starting days=$days seed=$seed")
                        val counts = seeder.seed(days, seed)
                        val summary = counts.entries.joinToString(", ") { "${it.key}=${it.value}" }
                        Log.i(TAG, "SEED DONE: $summary")
                    } catch (e: Exception) {
                        Log.e(TAG, "SEED DONE: error ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            "com.myhealth.seeder.CLEAR" -> {
                scope.launch {
                    try {
                        Log.i(TAG, "CLEAR: starting")
                        Seeder(appContext).clear()
                        Log.i(TAG, "CLEAR DONE")
                    } catch (e: Exception) {
                        Log.e(TAG, "CLEAR DONE: error ${e.message}", e)
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            else -> pendingResult.finish()
        }
    }
}
