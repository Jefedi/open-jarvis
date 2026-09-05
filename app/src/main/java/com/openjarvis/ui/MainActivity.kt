package com.openjarvis.ui

import android.os.Bundle
import android.content.Intent
import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openjarvis.murena.AssistantRuntime
import com.openjarvis.murena.MurenaApp
import com.openjarvis.ui.theme.OpenJarvisTheme

class MainActivity : ComponentActivity() {
    private var runtime: AssistantRuntime? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        try { runtime = AssistantRuntime.get(applicationContext) } catch (_: Exception) { }
        setContent {
            OpenJarvisTheme {
                val ready = runtime
                if (ready != null) MurenaApp(ready)
                else Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("Stockage chiffré indisponible", style = MaterialTheme.typography.headlineSmall)
                        Text("Déverrouillez le téléphone et réessayez. Jarvis n'utilisera pas de stockage non chiffré. Une restauration depuis un autre appareil peut nécessiter la réinitialisation des données dans les réglages Android.")
                        Button(onClick = { recreate() }) { Text("Réessayer") }
                    }
                }
            }
        }
    }
    override fun onStart() { super.onStart(); runtime?.audio?.foreground = true }
    override fun onStop() { runtime?.audio?.foreground = false; super.onStop() }
}
