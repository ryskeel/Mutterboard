package com.example.dictator

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dictator.ui.theme.DictatorTheme

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* status is re-read in onResume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DictatorTheme {
                SetupScreen(
                    onRequestMic = { requestMicPermission() },
                    onOpenImeSettings = { openImeSettings() }
                )
            }
        }
    }

    private fun requestMicPermission() {
        requestPermissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
    }

    private fun openImeSettings() {
        startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
    }
}

@Composable
private fun SetupScreen(
    onRequestMic: () -> Unit,
    onOpenImeSettings: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(
            DictatorInputMethodService.PREFS,
            Context.MODE_PRIVATE
        )
    }

    var apiKey by remember {
        mutableStateOf(prefs.getString(DictatorInputMethodService.KEY_API_KEY, "") ?: "")
    }
    var showKeyDialog by remember { mutableStateOf(false) }
    var hasMic by remember { mutableStateOf(false) }
    var imeEnabled by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        imeEnabled = isImeEnabled(context)
    }

    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
        ) {
            Text("Dictator keyboard", fontSize = 32.sp, fontWeight = FontWeight.Bold)

            HorizontalDivider()

            StepRow(
                step = "1",
                label = "Microphone permission",
                done = hasMic,
                actionLabel = "Grant",
                onAction = onRequestMic
            )
            StepRow(
                step = "2",
                label = "Enable keyboard",
                done = imeEnabled,
                actionLabel = "Open settings",
                onAction = onOpenImeSettings
            )
            StepRow(
                step = "3",
                label = "Groq API key",
                done = apiKey.isNotBlank(),
                doneLabel = "Saved",
                actionLabel = if (apiKey.isBlank()) "Add" else "Edit",
                onAction = { showKeyDialog = true }
            )

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Updates",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text("Get the latest release", fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(16.dp))
                OutlinedButton(onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/ryskeel/Dictator/releases/latest")
                        )
                    )
                }) {
                    Text("Open GitHub")
                }
            }
        }
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            initialKey = apiKey,
            onDismiss = { showKeyDialog = false },
            onSave = { newKey ->
                apiKey = newKey.trim()
                prefs.edit()
                    .putString(DictatorInputMethodService.KEY_API_KEY, apiKey)
                    .apply()
                showKeyDialog = false
            }
        )
    }
}

@Composable
private fun ApiKeyDialog(
    initialKey: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val context = LocalContext.current
    var draft by remember { mutableStateOf(initialKey) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Groq API key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys"))
                        )
                    },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("Need a key? Get one from Groq")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(draft) },
                enabled = draft.isNotBlank()
            ) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun StepRow(
    step: String,
    label: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    doneLabel: String = "Granted"
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Step $step", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                if (done) doneLabel else "Required",
                fontSize = 12.sp,
                color = if (done) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.width(16.dp))
        OutlinedButton(onClick = onAction) { Text(actionLabel) }
    }
}

private fun isImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val target = ComponentName(context, DictatorInputMethodService::class.java)
    return imm.enabledInputMethodList.any { it.component == target }
}
