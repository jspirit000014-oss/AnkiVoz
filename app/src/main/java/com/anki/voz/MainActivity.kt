package com.anki.voz

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private var tick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen(tick) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        tick++
    }
}

private fun isA11yOn(ctx: Context): Boolean {
    if (AnkiAccessibilityService.instance != null) return true
    val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        ?: return false
    val cn = ComponentName(ctx, AnkiAccessibilityService::class.java)
    return s.split(':').any {
        it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
    }
}

private fun micGranted(ctx: Context): Boolean =
    ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
private fun Screen(tick: Int) {
    val ctx = LocalContext.current
    var a11y by remember(tick) { mutableStateOf(isA11yOn(ctx)) }
    var mic by remember(tick) { mutableStateOf(micGranted(ctx)) }
    val running by ListenService.running.collectAsState()
    val status by ListenService.status.collectAsState()
    val dump by DumpStore.text.collectAsState()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { mic = micGranted(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("AnkiVoz", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "Control por voz del repaso de AnkiDroid. Solo actúa dentro de AnkiDroid; " +
                "el micrófono se apaga cuando sales de la app.",
            style = MaterialTheme.typography.bodyMedium
        )

        StepCard(
            title = (if (a11y) "✓ " else "✗ ") + "1. Servicio de accesibilidad",
            body = "Ajustes → Accesibilidad → AnkiVoz → Activar.\n" +
                "Si Android dice «Ajuste restringido»: Info de la app → ⋮ → Permitir ajustes restringidos."
        ) {
            Button(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text("Abrir accesibilidad") }
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${ctx.packageName}"))
                )
            }) { Text("Info de la app") }
        }

        StepCard(
            title = (if (mic) "✓ " else "✗ ") + "2. Permisos",
            body = "Micrófono (y notificaciones en Android 13+)."
        ) {
            Button(onClick = {
                val p = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) p.add(Manifest.permission.POST_NOTIFICATIONS)
                permLauncher.launch(p.toTypedArray())
            }, enabled = !mic) { Text("Conceder permisos") }
        }

        StepCard(
            title = (if (running) "● " else "○ ") + "3. Escucha",
            body = status
        ) {
            Button(
                onClick = {
                    val i = Intent(ctx, ListenService::class.java)
                    if (running) ctx.stopService(i) else ctx.startForegroundService(i)
                },
                enabled = running || (a11y && mic)
            ) { Text(if (running) "Detener" else "Iniciar") }
            OutlinedButton(onClick = {
                val intent = Cfg.ANKI_PACKAGES.firstNotNullOfOrNull {
                    ctx.packageManager.getLaunchIntentForPackage(it)
                }
                if (intent != null) ctx.startActivity(intent)
            }) { Text("Abrir AnkiDroid") }
        }

        StepCard(
            title = "Diagnóstico",
            body = "Pulsa «Volcar», cambia a AnkiDroid y deja una tarjeta con la respuesta visible. " +
                "A los 6 s se guarda el árbol de vistas aquí (repite con la pregunta y con la respuesta)."
        ) {
            Button(
                onClick = { AnkiAccessibilityService.instance?.dumpLater() },
                enabled = a11y
            ) { Text("Volcar en 6 s") }
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("ankivoz", dump))
            }, enabled = dump.isNotEmpty()) { Text("Copiar") }
            OutlinedButton(onClick = {
                DumpStore.text.value = ""
                DumpStore.count = 0
            }, enabled = dump.isNotEmpty()) { Text("Limpiar") }
            if (dump.isNotEmpty()) {
                SelectionContainer {
                    Text(dump, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Comandos", fontWeight = FontWeight.Bold)
                Text("«mostrar» → Mostrar respuesta")
                Text("«mal» / «otra vez» → Otra vez")
                Text("«difícil» → Difícil")
                Text("«bien» → Bien")
                Text("«fácil» → Fácil")
                Text("«leer» → lee la tarjeta en voz alta")
                Text("«pausa» / «reanudar»")
                Text("«salir» → apaga AnkiVoz")
                Spacer(Modifier.height(6.dp))
                Text(
                    "Las palabras se editan en assets/commands.json.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun StepCard(title: String, body: String, actions: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            actions()
        }
    }
}
