package com.openjarvis.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openjarvis.accessibility.JarvisAccessibilityService
import com.openjarvis.graphify.GraphifyRepository
import com.openjarvis.ui.dashboard.DashboardScreen
import com.openjarvis.ui.theme.OpenJarvisTheme
import com.openjarvis.ui.theme.VoidColor

class MainActivity : ComponentActivity() {
    private lateinit var graphifyRepo: GraphifyRepository
    private var accessibilityEnabled by mutableStateOf(false)
    private var overlayEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        graphifyRepo = GraphifyRepository(applicationContext)
        refreshPermissions()
        setContent {
            OpenJarvisTheme {
                if (!accessibilityEnabled || !overlayEnabled) {
                    PermissionScreen(
                        accessibilityEnabled = accessibilityEnabled,
                        overlayEnabled = overlayEnabled,
                        onEnableAccessibility = {
                            openSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onEnableOverlay = {
                            openSystemSettings(Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            ))
                        },
                        onOpenSettings = { openSettings() }
                    )
                } else {
                    DashboardScreen(
                        onStartOverlay = { startOverlayService() },
                        onOpenSettings = { openSettings() },
                        graphifyRepo = graphifyRepo,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Settings is another activity: refresh grants instead of keeping stale Compose state.
        refreshPermissions()
    }

    private fun refreshPermissions() {
        val expected = ComponentName(this, JarvisAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        accessibilityEnabled = enabled.split(':').any {
            ComponentName.unflattenFromString(it) == expected
        }
        overlayEnabled = Settings.canDrawOverlays(this)
    }

    private fun openSystemSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Ce réglage n'est pas disponible sur cette ROM.", Toast.LENGTH_LONG).show()
        }
    }

    private fun startOverlayService() {
        refreshPermissions()
        if (!accessibilityEnabled || !overlayEnabled) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
        } catch (_: SecurityException) {
            Toast.makeText(this, "Android a refusé le démarrage du service.", Toast.LENGTH_LONG).show()
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }
}

@Composable
fun PermissionScreen(
    accessibilityEnabled: Boolean,
    overlayEnabled: Boolean,
    onEnableAccessibility: () -> Unit,
    onEnableOverlay: () -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Autorisations", style = MaterialTheme.typography.headlineMedium, color = VoidColor.TextPrimary)
        Spacer(Modifier.height(16.dp))
        Text(
            "Le contrôle des applications nécessite l'accessibilité et l'affichage par-dessus les autres applications. Vous pouvez configurer les fournisseurs sans les activer.",
            style = MaterialTheme.typography.bodyMedium,
            color = VoidColor.TextSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        if (!accessibilityEnabled) {
            Button(onClick = onEnableAccessibility, modifier = Modifier.fillMaxWidth()) {
                Text("Ouvrir les réglages d'accessibilité")
            }
            Spacer(Modifier.height(16.dp))
        }
        if (!overlayEnabled) {
            Button(onClick = onEnableOverlay, modifier = Modifier.fillMaxWidth()) {
                Text("Autoriser l'affichage flottant")
            }
        }
        TextButton(onClick = onOpenSettings) { Text("Configurer les fournisseurs") }
        if (accessibilityEnabled && overlayEnabled) {
            Text("Autorisations accordées", color = VoidColor.Violet)
        }
    }
}
