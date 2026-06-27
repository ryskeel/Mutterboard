package com.example.mutterboard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.inputmethod.InputMethodManager
import androidx.core.content.FileProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mutterboard.ui.theme.MutterboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

private const val REPO = "ryskeel/Mutterboard"
private val GreenAccent = Color(0xFF2E7D32)
private val BrandFont = FontFamily(Font(R.font.audiowide_regular))

private data class ReleaseInfo(val tag: String, val htmlUrl: String, val apkUrl: String?)

private sealed interface UpdateStatus {
    object Checking : UpdateStatus
    data class UpToDate(val version: String) : UpdateStatus
    data class Available(val release: ReleaseInfo) : UpdateStatus
    object Failed : UpdateStatus
}

private sealed interface DownloadState {
    object Idle : DownloadState
    data class Downloading(val progress: Float) : DownloadState
    data class Ready(val file: File) : DownloadState
    object Failed : DownloadState
}

class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* status is re-read in onResume */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MutterboardTheme {
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
            MutterboardInputMethodService.PREFS,
            Context.MODE_PRIVATE
        )
    }

    var apiKey by remember {
        mutableStateOf(prefs.getString(MutterboardInputMethodService.KEY_API_KEY, "") ?: "")
    }
    var showKeyDialog by remember { mutableStateOf(false) }
    var hasMic by remember { mutableStateOf(false) }
    var imeEnabled by remember { mutableStateOf(false) }
    var refreshTick by remember { mutableStateOf(0) }

    val currentVersion = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "1.0"
    }
    var updateStatus by remember { mutableStateOf<UpdateStatus>(UpdateStatus.Checking) }
    var updateCheckTick by remember { mutableStateOf(0) }
    var downloadState by remember { mutableStateOf<DownloadState>(DownloadState.Idle) }
    val scope = rememberCoroutineScope()

    val modelManager = remember { ParakeetModelManager(context) }
    var engine by remember {
        mutableStateOf(Engine.fromPref(prefs.getString(MutterboardInputMethodService.KEY_ENGINE, null)))
    }
    var modelReady by remember { mutableStateOf(modelManager.isReady()) }
    var modelProgress by remember { mutableStateOf<ParakeetModelManager.Progress?>(null) }

    fun selectEngine(newEngine: Engine) {
        engine = newEngine
        prefs.edit().putString(MutterboardInputMethodService.KEY_ENGINE, newEngine.prefValue).apply()
    }

    fun downloadModel() {
        modelProgress = ParakeetModelManager.Progress.Downloading(0f)
        modelManager.download { p ->
            (context as? ComponentActivity)?.runOnUiThread {
                modelProgress = p
                if (p is ParakeetModelManager.Progress.Done) modelReady = true
            }
        }
    }

    LaunchedEffect(refreshTick) {
        hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        imeEnabled = isImeEnabled(context)
    }

    LaunchedEffect(updateCheckTick) {
        updateStatus = UpdateStatus.Checking
        val latest = fetchLatestRelease(REPO)
        updateStatus = when {
            latest == null -> UpdateStatus.Failed
            isNewer(latest.tag, currentVersion) -> UpdateStatus.Available(latest)
            else -> UpdateStatus.UpToDate(currentVersion)
        }
    }

    fun startUpdate(release: ReleaseInfo) {
        val apkUrl = release.apkUrl
        if (apkUrl == null) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.htmlUrl)))
            return
        }
        scope.launch {
            downloadState = DownloadState.Downloading(0f)
            val file = downloadApk(context, apkUrl) { progress ->
                downloadState = DownloadState.Downloading(progress)
            }
            if (file == null) {
                downloadState = DownloadState.Failed
            } else {
                downloadState = DownloadState.Ready(file)
                launchInstall(context, file)
            }
        }
    }

    DisposableEffect(Unit) {
        val activity = context as? ComponentActivity
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refreshTick++
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }

    val transcriberReady = if (engine == Engine.LOCAL) modelReady else apiKey.isNotBlank()
    val allDone = hasMic && imeEnabled && transcriberReady

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 24.dp)
        ) {
            Text("Mutterboard", fontSize = 30.sp, fontFamily = BrandFont)
            Spacer(Modifier.height(4.dp))
            Text(
                "Voice keyboard",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            SectionHeader("Setup")
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                StepRow(
                    label = "Microphone permission",
                    done = hasMic,
                    doneText = "Granted",
                    actionLabel = "Grant",
                    showActionWhenDone = false,
                    onAction = onRequestMic
                )
                HorizontalDivider(modifier = Modifier.padding(start = 62.dp))
                StepRow(
                    label = "Enable keyboard",
                    done = imeEnabled,
                    doneText = "Enabled",
                    actionLabel = "Enable",
                    showActionWhenDone = false,
                    onAction = onOpenImeSettings
                )
                HorizontalDivider(modifier = Modifier.padding(start = 62.dp))
                StepRow(
                    label = "Groq API key",
                    done = apiKey.isNotBlank(),
                    doneText = "Saved",
                    actionLabel = if (apiKey.isBlank()) "Add" else "Edit",
                    showActionWhenDone = true,
                    onAction = { showKeyDialog = true }
                )
            }

            if (allDone) {
                Spacer(Modifier.height(16.dp))
                CompletionBanner()
            }

            Spacer(Modifier.height(40.dp))

            SectionHeader("Transcription")
            Spacer(Modifier.height(12.dp))
            TranscriptionCard(
                engine = engine,
                modelReady = modelReady,
                modelProgress = modelProgress,
                onSelectEngine = { selectEngine(it) },
                onDownloadModel = { downloadModel() }
            )

            Spacer(Modifier.height(40.dp))

            SectionHeader("Updates")
            Spacer(Modifier.height(12.dp))
            UpdatesCard(
                status = updateStatus,
                downloadState = downloadState,
                currentVersion = currentVersion,
                onOpen = { url ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                },
                onCheck = {
                    downloadState = DownloadState.Idle
                    updateCheckTick++
                },
                onUpdate = { release -> startUpdate(release) },
                onInstall = { file -> launchInstall(context, file) }
            )
        }
    }

    if (showKeyDialog) {
        ApiKeyDialog(
            initialKey = apiKey,
            onDismiss = { showKeyDialog = false },
            onSave = { newKey ->
                apiKey = newKey.trim()
                prefs.edit()
                    .putString(MutterboardInputMethodService.KEY_API_KEY, apiKey)
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
    val haptic = rememberTapHaptic()
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
                onClick = { haptic(); onSave(draft) },
                enabled = draft.isNotBlank()
            ) { Text("Done") }
        },
        dismissButton = {
            TextButton(onClick = { haptic(); onDismiss() }) { Text("Cancel") }
        }
    )
}

/** Returns a callback that fires a light tap vibration, matching keyboard haptics. */
@Composable
private fun rememberTapHaptic(): () -> Unit {
    val view = LocalView.current
    return { view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun StepBadge(done: Boolean) {
    val base = Modifier.size(24.dp).clip(CircleShape)
    val styled = if (done) {
        base.background(GreenAccent)
    } else {
        base.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
    }
    Box(modifier = styled, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = if (done) "Done" else "Not done",
            tint = if (done) Color.White
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(15.dp)
        )
    }
}

@Composable
private fun StepRow(
    label: String,
    done: Boolean,
    doneText: String,
    actionLabel: String,
    showActionWhenDone: Boolean,
    onAction: () -> Unit
) {
    val haptic = rememberTapHaptic()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepBadge(done)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                if (done) doneText else "Required",
                fontSize = 12.sp,
                color = if (done) GreenAccent else MaterialTheme.colorScheme.error
            )
        }
        if (!done) {
            Spacer(Modifier.width(12.dp))
            Button(onClick = { haptic(); onAction() }) { Text(actionLabel) }
        } else if (showActionWhenDone) {
            Spacer(Modifier.width(12.dp))
            OutlinedButton(onClick = { haptic(); onAction() }) { Text(actionLabel) }
        }
    }
}

@Composable
private fun TranscriptionCard(
    engine: Engine,
    modelReady: Boolean,
    modelProgress: ParakeetModelManager.Progress?,
    onSelectEngine: (Engine) -> Unit,
    onDownloadModel: () -> Unit
) {
    val haptic = rememberTapHaptic()
    Card(modifier = Modifier.fillMaxWidth()) {
        EngineRow(
            label = "Cloud (Groq)",
            subtitle = "Fast, needs API key + internet",
            selected = engine == Engine.CLOUD,
            onSelect = { haptic(); onSelectEngine(Engine.CLOUD) }
        )
        HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
        EngineRow(
            label = "On-device (Parakeet)",
            subtitle = "Private, offline, English only",
            selected = engine == Engine.LOCAL,
            onSelect = { haptic(); onSelectEngine(Engine.LOCAL) }
        )

        if (engine == Engine.LOCAL) {
            HorizontalDivider(modifier = Modifier.padding(start = 54.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                when {
                    modelReady -> {
                        Text("Model ready", fontWeight = FontWeight.Medium, color = GreenAccent)
                        Text(
                            "Parakeet TDT 0.6B is installed on this device.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    modelProgress is ParakeetModelManager.Progress.Downloading -> {
                        Text("Downloading model…", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { modelProgress.fraction },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "${(modelProgress.fraction * 100).toInt()}%  ·  ~630 MB",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    modelProgress is ParakeetModelManager.Progress.Extracting -> {
                        Text("Extracting model…", fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        Text(
                            "Download required",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        if (modelProgress is ParakeetModelManager.Progress.Failed) {
                            Text(
                                modelProgress.message,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { haptic(); onDownloadModel() }) {
                            Text("Download model (~630 MB)")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineRow(
    label: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompletionBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = GreenAccent.copy(alpha = 0.12f)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Setup complete", fontWeight = FontWeight.Bold, color = GreenAccent)
            Text(
                "You're all good to start muttering.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UpdatesCard(
    status: UpdateStatus,
    downloadState: DownloadState,
    currentVersion: String,
    onOpen: (String) -> Unit,
    onCheck: () -> Unit,
    onUpdate: (ReleaseInfo) -> Unit,
    onInstall: (File) -> Unit
) {
    val releasesUrl = "https://github.com/$REPO/releases/latest"
    val available = (status as? UpdateStatus.Available)?.release

    // Title + subtitle: an active download takes priority over the check status.
    val title: String
    val titleColor: Color
    val subtitle: String
    when (downloadState) {
        is DownloadState.Downloading -> {
            title = "Downloading update…"
            titleColor = Color.Unspecified
            subtitle = "${(downloadState.progress * 100).toInt()}%"
        }
        is DownloadState.Ready -> {
            title = "Update downloaded"
            titleColor = GreenAccent
            subtitle = "Tap Install to finish"
        }
        is DownloadState.Failed -> {
            title = "Download failed"
            titleColor = MaterialTheme.colorScheme.error
            subtitle = "Tap Update to try again"
        }
        is DownloadState.Idle -> when (status) {
            is UpdateStatus.Checking -> {
                title = "Checking for updates…"; titleColor = Color.Unspecified
                subtitle = "Version v${currentVersion.normalizedVersion()}"
            }
            is UpdateStatus.UpToDate -> {
                title = "You're up to date"; titleColor = Color.Unspecified
                subtitle = "Version v${currentVersion.normalizedVersion()}"
            }
            is UpdateStatus.Available -> {
                title = "Update available"; titleColor = GreenAccent
                subtitle = "v${currentVersion.normalizedVersion()} → v${status.release.tag.normalizedVersion()}"
            }
            is UpdateStatus.Failed -> {
                title = "Couldn't check for updates"; titleColor = Color.Unspecified
                subtitle = "Version v${currentVersion.normalizedVersion()}"
            }
        }
    }

    val haptic = rememberTapHaptic()
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = if (titleColor == GreenAccent) FontWeight.Bold else FontWeight.Medium,
                    color = titleColor
                )
                Text(
                    subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (downloadState is DownloadState.Idle) {
                    val linkLabel = if (available != null) "Get it on GitHub" else "View on GitHub"
                    val linkUrl = available?.htmlUrl ?: releasesUrl
                    TextButton(
                        onClick = { haptic(); onOpen(linkUrl) },
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        Text(linkLabel)
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            when {
                downloadState is DownloadState.Downloading ->
                    CircularProgressIndicator(
                        progress = { downloadState.progress },
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp
                    )
                downloadState is DownloadState.Ready ->
                    Button(onClick = { haptic(); onInstall(downloadState.file) }) { Text("Install") }
                status is UpdateStatus.Checking ->
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                available != null ->
                    Button(onClick = { haptic(); onUpdate(available) }) { Text("Update") }
                else ->
                    OutlinedButton(onClick = { haptic(); onCheck() }) { Text("Check now") }
            }
        }
    }
}

private fun String.normalizedVersion(): String = trimStart('v', 'V')

private fun isNewer(latestTag: String, current: String): Boolean {
    fun parts(v: String) = v.trimStart('v', 'V').split(".", "-")
        .mapNotNull { it.toIntOrNull() }
    val a = parts(latestTag)
    val b = parts(current)
    val n = maxOf(a.size, b.size)
    for (i in 0 until n) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private suspend fun fetchLatestRelease(repo: String): ReleaseInfo? =
    withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            OkHttpClient().newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val json = JSONObject(body)
                val tag = json.optString("tag_name")
                if (tag.isBlank()) return@use null
                val htmlUrl = json.optString("html_url")
                val assets = json.optJSONArray("assets")
                var apkUrl: String? = null
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                            apkUrl = asset.optString("browser_download_url")
                            break
                        }
                    }
                }
                ReleaseInfo(tag, htmlUrl, apkUrl)
            }
        }.getOrNull()
    }

private suspend fun downloadApk(
    context: Context,
    url: String,
    onProgress: (Float) -> Unit
): File? = withContext(Dispatchers.IO) {
    runCatching {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "mutterboard-update.apk")
        val request = Request.Builder().url(url).build()
        OkHttpClient().newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val body = resp.body ?: return@use null
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var downloaded = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
            file
        }
    }.getOrNull()
}

private fun launchInstall(context: Context, file: File) {
    // On Android 8+ the user must allow this app to install unknown apps.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        !context.packageManager.canRequestPackageInstalls()
    ) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    context.startActivity(
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}

private fun isImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val target = ComponentName(context, MutterboardInputMethodService::class.java)
    return imm.enabledInputMethodList.any { it.component == target }
}
