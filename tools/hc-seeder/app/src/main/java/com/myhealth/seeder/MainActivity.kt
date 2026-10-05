package com.myhealth.seeder

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

private const val TAG = "HcSeeder"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissions = registerForActivityResult(
            PermissionController.createRequestPermissionResultContract()
        ) { granted ->
            val msg = "Granted ${granted.size}/${SeederPermissions.ALL.size} permissions"
            Log.i(TAG, msg)
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }

        setContent {
            var status by remember { mutableStateOf("HC Seeder ready.") }
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text("HC Seeder", style = MaterialTheme.typography.headlineMedium)
                        Text(status)
                        Button(onClick = { requestPermissions.launch(SeederPermissions.ALL) }) {
                            Text("Grant permissions")
                        }
                        Button(onClick = {
                            status = "Seeding 45 days..."
                            lifecycleScope.launch {
                                try {
                                    val counts = Seeder(applicationContext).seed(45, 42L)
                                    val summary = counts.entries.joinToString("\n") { "${it.key}=${it.value}" }
                                    Log.i(TAG, "SEED DONE: " + counts.entries.joinToString(", ") { "${it.key}=${it.value}" })
                                    status = "SEED DONE:\n$summary"
                                } catch (e: Exception) {
                                    Log.e(TAG, "SEED DONE: error ${e.message}", e)
                                    status = "Seed failed: ${e.message}"
                                }
                            }
                        }) {
                            Text("Seed 45 days")
                        }
                    }
                }
            }
        }
    }
}
