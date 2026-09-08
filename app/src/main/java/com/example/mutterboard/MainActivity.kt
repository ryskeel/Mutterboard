package com.example.mutterboard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import com.example.mutterboard.ui.theme.AccentContainerDark
import com.example.mutterboard.ui.theme.AccentContainerLight
import com.example.mutterboard.ui.theme.MutterboardTheme
import com.example.mutterboard.ui.theme.OnAccentContainerDark
import com.example.mutterboard.ui.theme.OnAccentContainerLight
import com.example.mutterboard.ui.theme.OnSuccessDark
import com.example.mutterboard.ui.theme.OnSuccessLight
import com.example.mutterboard.ui.theme.SuccessDark
import com.example.mutterboard.ui.theme.SuccessLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

private const val REPO = "ryskeel/Mutterboard"

private val BrandFont = FontFamily(Font(R.font.montserrat_black, FontWeight.Black))

// Theme-aware success accent (green = "ready/done"): lighter green on dark.
private val successColor: Color
    @Composable get() = if (isSystemInDarkTheme()) SuccessDark else SuccessLight

// Content color (e.g. a checkmark) drawn on top of successColor.
private val onSuccessColor: Color
    @Composable get() = if (isSystemInDarkTheme()) OnSuccessDark else OnSuccessLight

// Theme-aware peach "accent pill" (saved-key / model-ready chips, vocab badges):
// peach with dark content on light, warm brown with light content on dark.
private val accentContainerColor: Color
    @Composable get() = if (isSystemInDarkTheme()) AccentContainerDark else AccentContainerLight

private val onAccentContainerColor: Color
    @Composable get() = if (isSystemInDarkTheme()) OnAccentContainerDark else OnAccentContainerLight

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
        chooseOverlayByDefault()
        enableEdgeToEdge()
        setContent {
            MutterboardTheme {
                SetupScreen(
                    onRequestMic = { requestMicPermission() },
                    onOpenImeSettings = { openImeSettings() },
                    onOpenOverlaySettings = { openOverlaySettings() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onRequestNotifications = { requestNotificationPermission() },
                    onRemoveShortcut = { openAccessibilityShortcutSettings() }
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

    private fun openOverlaySettings() {
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    /**
     * Opens the shortcuts screen, where the accessibility button can be taken off
     * Mutterboard. The app cannot flip that toggle itself - the setting behind it
     * is signature-level - so landing the user on the right screen is the most it
     * can do.
     *
     * The per-service detail page would be one step closer, but it is gated
     * behind OPEN_ACCESSIBILITY_DETAILS_SETTINGS (signature|installer). This
     * action is a plain exported intent with no such gate.
     */
    private fun openAccessibilityShortcutSettings() {
        val intent = Intent("android.settings.ACCESSIBILITY_SHORTCUT_SETTINGS")
        runCatching { startActivity(intent) }.onFailure {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    /**
     * The overlay runs as a foreground service, which posts an ongoing
     * notification. Without this grant that notification is silently hidden, so
     * ask at the moment the user turns the overlay on.
     */
    /**
     * The overlay is how this app is meant to be used, so a fresh install starts
     * on it rather than on the keyboard.
     *
     * Written once, on first launch, and then it is the user's: the component
     * states are the choice, so a default that kept re-asserting itself would
     * quietly undo someone who picked the keyboard.
     */
    private fun chooseOverlayByDefault() {
        val prefs = getSharedPreferences(MutterboardInputMethodService.PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_MODE_CHOSEN)) return
        setOverlayLauncherEnabled(this, true)
        prefs.edit().putBoolean(KEY_MODE_CHOSEN, true).apply()
    }

    /**
     * The app icon changes hands here rather than the moment the switch moves,
     * because disabling the alias this screen is running on destroys it. See
     * syncLauncherIcons.
     */
    override fun onStop() {
        super.onStop()
        syncLauncherIcons(this)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    companion object {
        /** Set once the app has picked, or the user has picked, a way to dictate. */
        const val KEY_MODE_CHOSEN = "dictation_mode_chosen"
    }
}

@Composable
private fun SetupScreen(
    onRequestMic: () -> Unit,
    onOpenImeSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestNotifications: () -> Unit,
    onRemoveShortcut: () -> Unit
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
    var showRemoveKey by remember { mutableStateOf(false) }
    var showEngineInfo by remember { mutableStateOf(false) }
    var showDeleteModel by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
    var hasMic by remember { mutableStateOf(false) }
    var imeEnabled by remember { mutableStateOf(false) }
    var overlayEnabled by remember { mutableStateOf(false) }
    var canDrawOverlays by remember { mutableStateOf(false) }
    var accessibilityEnabled by remember { mutableStateOf(false) }
    var shortcutAttached by remember { mutableStateOf(false) }
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

    var customWords by remember {
        mutableStateOf(
            MutterboardInputMethodService.parseCustomWords(
                prefs.getString(MutterboardInputMethodService.KEY_CUSTOM_WORDS, null)
            )
        )
    }
    var showVocabEditor by remember { mutableStateOf(false) }

    fun saveCustomWords(words: List<String>) {
        customWords = words
        prefs.edit()
            .putString(MutterboardInputMethodService.KEY_CUSTOM_WORDS, words.joinToString("\n"))
            .apply()
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

    fun deleteModel() {
        modelManager.deleteModel()
        modelReady = false
        modelProgress = null
        // Without the model, on-device can't run — fall back to Cloud.
        if (engine == Engine.LOCAL) selectEngine(Engine.CLOUD)
    }

    LaunchedEffect(refreshTick) {
        hasMic = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        imeEnabled = isImeEnabled(context)
        // All three are granted on screens outside this app, so they can only be
        // re-read on resume; refreshTick already fires there.
        overlayEnabled = isOverlayLauncherEnabled(context)
        canDrawOverlays = Settings.canDrawOverlays(context)
        accessibilityEnabled = isAccessibilityEnabled(context)
        shortcutAttached = hasAccessibilityShortcut(context)
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

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.ic_mutterboard_mark),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Mutterboard", fontSize = 30.sp, fontFamily = BrandFont)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Voice keyboard",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 46.dp)
            )

            Spacer(Modifier.height(32.dp))

            SectionHeader("Device setup")
            Spacer(Modifier.height(12.dp))
            Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
                StepRow(
                    label = "Microphone permission",
                    done = hasMic,
                    actionLabel = "Grant",
                    onAction = onRequestMic
                )
            }

            Spacer(Modifier.height(40.dp))

            SectionHeader("Transcription")
            Spacer(Modifier.height(12.dp))
            TranscriptionCard(
                engine = engine,
                apiKey = apiKey,
                modelReady = modelReady,
                modelProgress = modelProgress,
                onSelectEngine = { selectEngine(it) },
                onAddKey = { showKeyDialog = true },
                onRequestRemoveKey = { showRemoveKey = true },
                onDownloadModel = { downloadModel() },
                onLearnMore = { showEngineInfo = true },
                onRequestDeleteModel = { showDeleteModel = true }
            )
            Spacer(Modifier.height(40.dp))

            // The two ways in are alternatives, not a checklist. Enabling the
            // keyboard was step 2 of setup back when it was the only way to
            // dictate; left there it reads as required, which it is not, and it
            // invites having both running at once - which nobody wants and which
            // nothing in here arbitrates.
            SectionHeader("How you dictate")
            Spacer(Modifier.height(12.dp))
            DictationModeCard(
                overlayChosen = overlayEnabled,
                imeEnabled = imeEnabled,
                canDrawOverlays = canDrawOverlays,
                accessibilityEnabled = accessibilityEnabled,
                onChoose = { overlay ->
                    setOverlayLauncherEnabled(context, overlay)
                    overlayEnabled = overlay
                    if (overlay) onRequestNotifications()
                },
                onOpenImeSettings = onOpenImeSettings,
                onOpenOverlaySettings = onOpenOverlaySettings,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                shortcutAttached = shortcutAttached,
                onRemoveShortcut = onRemoveShortcut
            )


            Spacer(Modifier.height(40.dp))

            SectionHeader("Vocabulary")
            Spacer(Modifier.height(12.dp))
            VocabularyCard(
                words = customWords,
                onEdit = { showVocabEditor = true }
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

            Spacer(Modifier.height(24.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TextButton(onClick = { showPrivacy = true }) {
                    Text(
                        "Privacy policy",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    fun saveApiKey(newKey: String) {
        apiKey = newKey.trim()
        prefs.edit()
            .putString(MutterboardInputMethodService.KEY_API_KEY, apiKey)
            .apply()
    }

    if (showKeyDialog) {
        AddApiKeyDialog(
            initial = apiKey,
            onDismiss = { showKeyDialog = false },
            onSave = { newKey ->
                saveApiKey(newKey)
                showKeyDialog = false
            }
        )
    }

    if (showRemoveKey) {
        RemoveKeyDialog(
            onDismiss = { showRemoveKey = false },
            onConfirm = {
                saveApiKey("")
                showRemoveKey = false
            }
        )
    }

    if (showEngineInfo) {
        EngineInfoDialog(onDismiss = { showEngineInfo = false })
    }

    if (showDeleteModel) {
        DeleteModelDialog(
            onDismiss = { showDeleteModel = false },
            onConfirm = {
                deleteModel()
                showDeleteModel = false
            }
        )
    }

    if (showPrivacy) {
        PrivacyPolicyDialog(onDismiss = { showPrivacy = false })
    }

    if (showVocabEditor) {
        VocabularyEditSheet(
            initialWords = customWords,
            onClose = { showVocabEditor = false },
            onSave = { words ->
                saveCustomWords(words)
                showVocabEditor = false
            }
        )
    }
}

@Composable
private fun PrivacyPolicyDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Privacy policy") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                Text(
                    PRIVACY_POLICY_UPDATED,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    PRIVACY_POLICY_TEXT,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun DeleteModelDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val haptic = rememberTapHaptic()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete the model?") },
        text = {
            Text(
                "This removes the on-device model and frees up about 630 MB. " +
                    "You'll switch to the Default option until you download it again."
            )
        },
        confirmButton = {
            TextButton(onClick = { haptic(); onConfirm() }) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = { haptic(); onDismiss() }) { Text("Cancel") }
        }
    )
}

@Composable
private fun EngineInfoDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How transcription works") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Default", fontWeight = FontWeight.Bold)
                    Text(
                        "Everything runs over an encrypted connection to Groq's servers. " +
                            "Your recording is sent there, transcribed by OpenAI's Whisper " +
                            "Large v3 model, then passed through Alibaba's Qwen3.6 model to " +
                            "tidy up the phrasing and punctuation. The finished text " +
                            "is sent right back to your phone.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Mutterboard never saves your recordings. Each one lives only in " +
                            "a temporary file while it's being transcribed, and is deleted " +
                            "the moment it's done, so nothing is kept afterward. No accounts, " +
                            "no analytics, no tracking. You just need an internet " +
                            "connection and a free Groq API key.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Offline", fontWeight = FontWeight.Bold)
                    Text(
                        "Transcription runs entirely on your phone using NVIDIA's " +
                            "Parakeet model. It's quick, works without any internet " +
                            "connection, and your audio never leaves the device. The " +
                            "tradeoff is that the wording isn't cleaned up the way " +
                            "Default does it, so results won't be quite as polished. " +
                            "It's also English-only and needs a one-time ~630 MB " +
                            "model download.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Bonus: with the model downloaded, Default automatically " +
                            "switches to Offline whenever you have no internet, so " +
                            "dictation keeps working in airplane mode or a dead zone.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Got it") }
        }
    )
}

@Composable
private fun AddApiKeyDialog(
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    initial: String = ""
) {
    val context = LocalContext.current
    val haptic = rememberTapHaptic()
    var draft by remember { mutableStateOf(initial) }
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

/**
 * Inline saved-key chip on the Cloud option, mirroring the on-device "model
 * ready" row: a peach pill confirming the key is saved, with an edit icon (to
 * change the key via the dialog) and a trailing delete icon. The raw key is
 * never shown here — masking it added no value since it couldn't be copied.
 */
@Composable
private fun SavedKeyRow(onRequestEdit: () -> Unit, onRequestRemove: () -> Unit) {
    val haptic = rememberTapHaptic()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accentContainerColor)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = null,
            tint = onAccentContainerColor,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "API key saved",
            fontSize = 12.sp,
            color = onAccentContainerColor,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { haptic(); onRequestEdit() },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.Edit,
                contentDescription = "Edit key",
                tint = onAccentContainerColor,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(
            onClick = { haptic(); onRequestRemove() },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.DeleteOutline,
                contentDescription = "Remove key",
                tint = onAccentContainerColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Custom vocabulary summary. The word list applies to both engines (it's fed to
 * Whisper as a prompt on Cloud and fuzzy-matched against output on-device), so it
 * lives in its own section rather than under either engine. Rather than editing
 * inline — where it was too easy to tap a word and delete it by accident — the
 * card shows a compact peach summary pill (matching the saved-key / model-ready
 * rows) with an edit icon; tapping it opens [VocabularyEditSheet] to make changes.
 */
@Composable
private fun VocabularyCard(
    words: List<String>,
    onEdit: () -> Unit
) {
    val haptic = rememberTapHaptic()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Add words that Mutterboard should remember.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(14.dp))
            if (words.isEmpty()) {
                Button(onClick = { haptic(); onEdit() }) { Text("Add words") }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { haptic(); onEdit() }
                        .background(accentContainerColor)
                        .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                ) {
                    Text(
                        text = vocabSummary(words),
                        fontSize = 13.sp,
                        color = onAccentContainerColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Edit words",
                        tint = onAccentContainerColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Builds the one-line vocabulary summary shown on the card: the first few words
 * followed by a "+N more" count, e.g. "Mutterboard, Groq, Parakeet, +5 more".
 */
private fun vocabSummary(words: List<String>): String {
    // Newest first: stored order is oldest-first, so reverse before taking the head.
    val shown = words.asReversed().take(3)
    val rest = words.size - shown.size
    return shown.joinToString(", ") + if (rest > 0) ", +$rest more" else ""
}

/** A single custom word as a peach badge with a × to remove it. */
@Composable
private fun WordBadge(word: String, onRemove: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accentContainerColor)
            .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Text(word, fontSize = 13.sp, color = onAccentContainerColor)
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Remove $word",
                tint = onAccentContainerColor,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Vocabulary editor, presented as a [ModalBottomSheet]. The sheet handles its own
 * window, insets, edge-to-edge bars, and back dispatch, so the back gesture/button
 * dismisses it cleanly without the per-version system-bar fights a stretched dialog
 * ran into. It rises over a scrim with rounded top corners, stopping short of the
 * status bar. Edits touch only a local copy of the word list — nothing persists
 * until Save — so an accidental tap (the reason inline editing was dropped) is never
 * destructive, and leaving with unsaved changes prompts a discard confirmation.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun VocabularyEditSheet(
    initialWords: List<String>,
    onClose: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    val haptic = rememberTapHaptic()
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var words by remember { mutableStateOf(initialWords) }
    var draft by remember { mutableStateOf("") }
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val dirty = words != initialWords

    // Animate the sheet down, then run [after] once it's actually hidden.
    fun dismissSheet(after: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) after()
        }
    }

    // Every exit route (back arrow, system back, swipe-down, scrim tap) funnels
    // here. With unsaved edits we confirm first; the dismiss gestures hide the sheet
    // before calling this, so re-show it so the discard dialog reads over the sheet
    // rather than over an empty screen.
    fun attemptClose() {
        if (dirty) {
            showDiscardConfirm = true
            if (!sheetState.isVisible) scope.launch { sheetState.show() }
        } else {
            dismissSheet(onClose)
        }
    }

    fun addDraft() {
        val cleaned = draft.trim()
        if (cleaned.isEmpty()) return
        // Case-insensitive de-dupe so "Mutterboard" and "mutterboard" don't both stick.
        // Stored in insertion order (oldest first); the list is reversed at display
        // time so the newest word shows at the top.
        if (words.none { it.equals(cleaned, ignoreCase = true) }) {
            words = words + cleaned
        }
        draft = ""
    }

    ModalBottomSheet(
        onDismissRequest = { attemptClose() },
        sheetState = sheetState,
        containerColor = accentContainerColor,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
                .imePadding()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { haptic(); attemptClose() },
                    modifier = Modifier.offset(x = (-12).dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = onAccentContainerColor
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { haptic(); dismissSheet { onSave(words) } }) {
                    Text("Save", color = onAccentContainerColor, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(Modifier.height(4.dp))
            Text(
                "Custom words",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = onAccentContainerColor
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Add names, jargon, or brands you want Mutterboard to get right.",
                fontSize = 13.sp,
                color = onAccentContainerColor.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(16.dp))

            // White card holding the badges. It's the flexible row (weight, fill =
            // false) so it grows with the word count up to a cap, but yields space —
            // shrinking and scrolling internally — when the keyboard is up, instead
            // of crushing the fixed input row below it.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .heightIn(min = 140.dp, max = 420.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                if (words.isEmpty()) {
                    Text(
                        "No words yet.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Newest first: stored oldest-first, shown reversed.
                        words.asReversed().forEach { word ->
                            WordBadge(
                                word = word,
                                onRemove = { haptic(); words = words.filterNot { it == word } }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text("Add a word") },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { haptic(); addDraft() },
                    enabled = draft.isNotBlank()
                ) { Text("Add") }
            }
        }
    }

    if (showDiscardConfirm) {
        AlertDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text("Discard changes?") },
            text = { Text("You've added or removed words without saving. Leave anyway?") },
            confirmButton = {
                TextButton(onClick = {
                    haptic(); showDiscardConfirm = false; dismissSheet(onClose)
                }) {
                    Text("Discard", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { haptic(); showDiscardConfirm = false }) { Text("Keep editing") }
            }
        )
    }
}

@Composable
private fun RemoveKeyDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val haptic = rememberTapHaptic()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove the API key?") },
        text = {
            Text(
                "This deletes your saved Groq key from this device. You'll need to " +
                    "add a key again to use Default transcription."
            )
        },
        confirmButton = {
            TextButton(onClick = { haptic(); onConfirm() }) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
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
private fun StepBadge(done: Boolean, step: Int? = null) {
    val base = Modifier.size(20.dp).clip(CircleShape)
    val styled = if (done) {
        base.background(successColor)
    } else {
        base.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
    }
    Box(modifier = styled, contentAlignment = Alignment.Center) {
        // An unfinished step in an ordered list shows its number, so the card
        // reads as a sequence to work through rather than a list of failures.
        if (!done && step != null) {
            Text(
                step.toString(),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // Without this the glyph sits low in the circle: font padding
                // and the default line height are both taller than the digit,
                // and a 20dp badge has no room to hide either.
                style = LocalTextStyle.current.merge(
                    lineHeight = 11.sp,
                    platformStyle = PlatformTextStyle(includeFontPadding = false),
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Center,
                        trim = LineHeightStyle.Trim.Both
                    )
                )
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = if (done) "Done" else "Not done",
                tint = if (done) onSuccessColor
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

@Composable
private fun StepRow(
    label: String,
    done: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    optional: Boolean = false,
    step: Int? = null,
    note: String? = null
) {
    val haptic = rememberTapHaptic()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StepBadge(done, step)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
            // When done, the checkmark says it all — no "Granted/Enabled" subtext.
            if (!done) {
                Text(
                    note ?: if (optional) "Optional" else "Required",
                    fontSize = 12.sp,
                    // Only a genuinely missing requirement is worth alarming
                    // about. A step that simply has not been reached yet reads
                    // as breakage in red.
                    color = if (optional || note != null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
        if (!done) {
            Spacer(Modifier.width(12.dp))
            Button(onClick = { haptic(); onAction() }) { Text(actionLabel) }
        }
    }
}

/**
 * One of the two ways in, as a radio row. Tapping anywhere on the row picks it:
 * the description is the part that tells you what you are choosing, so it should
 * not be the one part that is not a target.
 */
@Composable
private fun ModeChoiceRow(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    val haptic = rememberTapHaptic()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = { haptic(); onSelect() }
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        // The dot shares a row with the title and nothing else, so centring that
        // row centres it on the title by construction. Boxing it to a guessed
        // line height did not: the title's real line box is shorter than the
        // 24dp the style nominates, which left the dot sitting high.
        Row(verticalAlignment = Alignment.CenterVertically) {
            OptionRadio(selected)
            Spacer(Modifier.width(OPTION_TEXT_INSET - RADIO_SIZE))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
        Text(
            description,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = OPTION_TEXT_INSET)
        )
    }
}

/**
 * Turning on the accessibility service makes Android attach its own shortcut,
 * which parks a button on screen that Mutterboard never uses and cannot remove
 * itself. Presented as the next step in the sequence rather than as a warning:
 * it is a normal consequence of the previous step, not something the user got
 * wrong.
 */
@Composable
private fun ShortcutStepRow(done: Boolean, step: Int?, onAction: () -> Unit) {
    StepRow(
        label = "Turn off Android's shortcut button",
        done = done,
        actionLabel = "Turn off",
        onAction = onAction,
        step = step,
        note = "Android adds this on its own. Mutterboard never uses it."
    )
}

/**
 * Which of the two ways into Mutterboard you are using, and the setup left for
 * it.
 *
 * A choice rather than two switches. They are alternatives - the overlay floats
 * over any app and the keyboard only runs inside a text field you switched to -
 * and nothing in the app arbitrates between them if both are live, so offering
 * both at once is offering a state nobody wants.
 *
 * The overlay's own state is the choice: there is no separate preference that
 * could drift out of sync with which components are enabled.
 */
@Composable
private fun DictationModeCard(
    overlayChosen: Boolean,
    imeEnabled: Boolean,
    canDrawOverlays: Boolean,
    accessibilityEnabled: Boolean,
    onChoose: (Boolean) -> Unit,
    onOpenImeSettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    shortcutAttached: Boolean,
    onRemoveShortcut: () -> Unit
) {
    val haptic = rememberTapHaptic()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        ModeChoiceRow(
            label = "Overlay",
            description = "Dictate from anywhere, in a text field or not. The " +
                "transcript goes to your clipboard, and pastes itself into the " +
                "field if one is open.",
            selected = overlayChosen,
            onSelect = { onChoose(true) }
        )
        // Each option's setup sits directly under it, indented, with no divider
        // between the two. A line there made the steps read as items in the same
        // list as the options rather than as what the option above asks of you -
        // and with both blocks collected at the bottom, the overlay's steps
        // appeared under the word "Keyboard".
        if (overlayChosen) {
            OptionSteps {
                StepRow(
                    label = "Display over other apps",
                    done = canDrawOverlays,
                    actionLabel = "Allow",
                    onAction = onOpenOverlaySettings,
                    step = 1
                )
                HorizontalDivider(modifier = Modifier.padding(start = 36.dp))
                StepRow(
                    label = "Paste into the field you are in",
                    done = accessibilityEnabled,
                    actionLabel = "Enable",
                    onAction = onOpenAccessibilitySettings,
                    // Still genuinely optional - the transcript lands on the
                    // clipboard either way - but "Optional" as a status line
                    // reads like a warning about the step rather than a
                    // description of it. The sentence below says what you give up.
                    note = "Lets Mutterboard paste for you.",
                    step = 2
                )
                if (!accessibilityEnabled) {
                    Text(
                        "Without this, transcripts are copied to your clipboard and you paste them yourself.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 36.dp, end = 16.dp, bottom = 14.dp)
                    )
                }
                // Only reachable once step 2 is done, because Android attaches
                // the shortcut when the service goes on. Shown even when already
                // clear so it reads as a step that is finished rather than a
                // warning that appears out of nowhere.
                if (accessibilityEnabled) {
                    HorizontalDivider(modifier = Modifier.padding(start = 36.dp))
                    ShortcutStepRow(
                        done = !shortcutAttached,
                        step = 3,
                        onAction = onRemoveShortcut
                    )
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        ModeChoiceRow(
            label = "Keyboard",
            description = "A normal keyboard you switch to from your usual one. " +
                "It dictates, types the text in, then switches straight back. " +
                "Worth choosing if your phone gives you no button to map the " +
                "overlay to.",
            selected = !overlayChosen,
            onSelect = { onChoose(false) }
        )
        if (!overlayChosen) {
            OptionSteps {
                StepRow(
                    label = "Enable keyboard",
                    done = imeEnabled,
                    actionLabel = "Enable",
                    onAction = onOpenImeSettings,
                    step = 1
                )
            }
        }
        // Leftover case: the overlay is off while the accessibility service, and
        // the shortcut Android attached to it, are still on. It belongs to
        // neither option - it is something to clean up - so it sits below both.
        if (!overlayChosen && shortcutAttached) {
            HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
            ShortcutStepRow(done = false, step = null, onAction = onRemoveShortcut)
        }
    }
}

/**
 * The dot for an option row.
 *
 * requiredSize sheds the 48dp touch target RadioButton reserves, which otherwise
 * floats the dot ~14dp in from where the step badges in the same card sit. The
 * whole option row is the tap target, so nothing is lost.
 */
@Composable
private fun OptionRadio(selected: Boolean) {
    RadioButton(
        selected = selected,
        onClick = null,
        modifier = Modifier.requiredSize(RADIO_SIZE)
    )
}

private val RADIO_SIZE = 20.dp

/** Where an option's text starts, matching the step rows' labels in the same card. */
private val OPTION_TEXT_INSET = 36.dp

/** The setup an option asks of you, indented so it reads as belonging to it. */
@Composable
private fun OptionSteps(content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(start = 20.dp), content = content)
}

@Composable
private fun TranscriptionCard(
    engine: Engine,
    apiKey: String,
    modelReady: Boolean,
    modelProgress: ParakeetModelManager.Progress?,
    onSelectEngine: (Engine) -> Unit,
    onAddKey: () -> Unit,
    onRequestRemoveKey: () -> Unit,
    onDownloadModel: () -> Unit,
    onLearnMore: () -> Unit,
    onRequestDeleteModel: () -> Unit
) {
    val haptic = rememberTapHaptic()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        EngineOption(
            label = "Default",
            subtitle = "(Recommended) The best Mutterboard experience with ultra fast transcription",
            selected = engine == Engine.CLOUD,
            onSelect = { haptic(); onSelectEngine(Engine.CLOUD) }
        ) {
            // The presence of the button is implicit enough — no "required" text.
            if (apiKey.isBlank()) {
                Button(onClick = { haptic(); onAddKey() }) { Text("Add API key") }
            } else {
                SavedKeyRow(onRequestEdit = onAddKey, onRequestRemove = onRequestRemoveKey)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        EngineOption(
            label = "Offline",
            subtitle = "Quick and runs fully offline, but results won't be quite as polished",
            selected = engine == Engine.LOCAL,
            onSelect = { haptic(); onSelectEngine(Engine.LOCAL) }
        ) {
            when {
                modelReady -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentContainerColor)
                            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = onAccentContainerColor,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Model downloaded",
                            fontSize = 12.sp,
                            color = onAccentContainerColor,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        IconButton(
                            onClick = { haptic(); onRequestDeleteModel() },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Delete model",
                                tint = onAccentContainerColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
                modelProgress is ParakeetModelManager.Progress.Downloading -> {
                    Text("Downloading model…", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { modelProgress.fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(modelProgress.fraction * 100).toInt()}%  ·  ~630 MB",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                modelProgress is ParakeetModelManager.Progress.Extracting -> {
                    Text("Extracting model…", fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                else -> {
                    // A failed attempt still shows why; the plain "required" text
                    // is dropped — the download button is implicit enough.
                    if (modelProgress is ParakeetModelManager.Progress.Failed) {
                        Text(
                            modelProgress.message,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                    Button(onClick = { haptic(); onDownloadModel() }) {
                        Text("Download model (~630 MB)")
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        TextButton(
            onClick = { haptic(); onLearnMore() },
            modifier = Modifier.padding(start = 8.dp)
        ) {
            Text("Learn more")
        }
    }
}

/**
 * A radio-selectable engine row. When [selected], [content] is rendered beneath
 * the subtitle — aligned under the label text and within the same tap group, so
 * the option's requirement (API key / model download) reads as part of it.
 */
@Composable
private fun EngineOption(
    label: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OptionRadio(selected)
            Spacer(Modifier.width(OPTION_TEXT_INSET - RADIO_SIZE))
            Text(label, fontWeight = FontWeight.SemiBold)
        }
        Column(modifier = Modifier.padding(start = OPTION_TEXT_INSET)) {
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (selected) {
                Spacer(Modifier.height(4.dp))
                content()
            }
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
            titleColor = successColor
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
                title = "Update available"; titleColor = successColor
                subtitle = "v${currentVersion.normalizedVersion()} → v${status.release.tag.normalizedVersion()}"
            }
            is UpdateStatus.Failed -> {
                title = "Couldn't check for updates"; titleColor = Color.Unspecified
                subtitle = "Version v${currentVersion.normalizedVersion()}"
            }
        }
    }

    val haptic = rememberTapHaptic()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = if (titleColor == successColor) FontWeight.Bold else FontWeight.Medium,
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

/**
 * Whether the overlay's launcher activity is turned on. It ships disabled, so
 * this doubles as "has the user opted into the overlay at all" — there is no
 * separate preference to drift out of sync with it.
 */
private fun isOverlayLauncherEnabled(context: Context): Boolean {
    val component = ComponentName(context, OverlayLauncherActivity::class.java)
    return context.packageManager.getComponentEnabledSetting(component) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
}

/**
 * Turns both ways into the overlay on or off together: the launcher activity a
 * side button can be mapped to, and the Quick Settings tile for phones with
 * nothing to map. Neither should exist while the feature is off.
 *
 * The app icon goes with them. Both entry points need a LAUNCHER activity — the
 * mappers only list launchable apps — so leaving both enabled put two Mutterboard
 * icons in the drawer, which is not a thing apps do. Instead the icon changes
 * hands: with the overlay on, tapping Mutterboard starts talking, and settings
 * lives on the icon's long-press shortcut and the band's own settings button.
 */
private fun setOverlayLauncherEnabled(context: Context, enabled: Boolean) {
    val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    for (component in listOf(
        ComponentName(context, OverlayLauncherActivity::class.java),
        ComponentName(context, MutterboardTileService::class.java)
    )) {
        context.packageManager.setComponentEnabledSetting(
            component,
            state,
            PackageManager.DONT_KILL_APP
        )
    }
    // The settings alias is deliberately NOT touched here. See syncLauncherIcons.
}

/** The alias that puts a settings icon in the drawer. */
private fun settingsLauncherComponent(context: Context) =
    ComponentName(context.packageName, "${context.packageName}.SettingsLauncher")

/**
 * Makes sure exactly one app icon exists, whichever way the overlay is set.
 *
 * **Only safe to call when the settings screen is not on screen.** Disabling a
 * component destroys any activity currently running on it, and this screen is
 * running on the alias it disables: called from the toggle, it took the whole
 * app down as the switch animated, which reads exactly like a crash. So it runs
 * from onStop instead, once the screen the user is looking at is not the thing
 * being turned off. DONT_KILL_APP does not help - the process survives, the
 * activity does not.
 *
 * It also repairs installs that predate the icon changing hands: component
 * states survive an update, so anyone who had the overlay on already would come
 * out with both icons enabled and nothing to ever fix it.
 */
internal fun syncLauncherIcons(context: Context) {
    val overlayOn = isOverlayLauncherEnabled(context)
    val settingsState = context.packageManager
        .getComponentEnabledSetting(settingsLauncherComponent(context))
    // DEFAULT means "as the manifest declares it", which for the alias is on.
    val settingsOn = settingsState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    if (settingsOn != overlayOn) return
    setOverlayLauncherEnabled(context, overlayOn)
}

/**
 * Whether Android has wired its accessibility shortcut (the floating button, or
 * the one in the navigation bar) to our service.
 *
 * It does this by itself when the user enables the service - our service never
 * asks for it, and dumpsys confirms requestA11yBtn=false. An app cannot undo it
 * either: that setting is only writable with WRITE_SECURE_SETTINGS, which is
 * signature-level. Reading it is allowed, so the most the app can do is notice
 * and hand the user the switch. Which is worth doing: an unexplained button
 * parked on your screen is exactly what this app exists to not be.
 */
private fun hasAccessibilityShortcut(context: Context): Boolean {
    val target = ComponentName(context, MutterboardAccessibilityService::class.java)
    val targets = Settings.Secure.getString(
        context.contentResolver,
        "accessibility_button_targets"
    ) ?: return false
    return targets.split(':').any { ComponentName.unflattenFromString(it) == target }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val target = ComponentName(context, MutterboardAccessibilityService::class.java)
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { ComponentName.unflattenFromString(it) == target }
}

private fun isImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val target = ComponentName(context, MutterboardInputMethodService::class.java)
    return imm.enabledInputMethodList.any { it.component == target }
}
