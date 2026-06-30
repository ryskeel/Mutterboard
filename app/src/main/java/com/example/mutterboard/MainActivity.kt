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
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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
    var showRemoveKey by remember { mutableStateOf(false) }
    var showEngineInfo by remember { mutableStateOf(false) }
    var showDeleteModel by remember { mutableStateOf(false) }
    var showPrivacy by remember { mutableStateOf(false) }
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

    var refineEnabled by remember {
        mutableStateOf(prefs.getBoolean(MutterboardInputMethodService.KEY_REFINE, false))
    }

    fun setRefine(enabled: Boolean) {
        refineEnabled = enabled
        prefs.edit().putBoolean(MutterboardInputMethodService.KEY_REFINE, enabled).apply()
    }

    var shakeToStop by remember {
        mutableStateOf(prefs.getBoolean(MutterboardInputMethodService.KEY_SHAKE_TO_STOP, false))
    }

    fun setShakeToStop(enabled: Boolean) {
        shakeToStop = enabled
        prefs.edit().putBoolean(MutterboardInputMethodService.KEY_SHAKE_TO_STOP, enabled).apply()
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
                HorizontalDivider(modifier = Modifier.padding(start = 52.dp))
                StepRow(
                    label = "Enable keyboard",
                    done = imeEnabled,
                    actionLabel = "Enable",
                    onAction = onOpenImeSettings
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
                refineEnabled = refineEnabled,
                onToggleRefine = { setRefine(it) },
                onSelectEngine = { selectEngine(it) },
                onAddKey = { showKeyDialog = true },
                onRequestRemoveKey = { showRemoveKey = true },
                onDownloadModel = { downloadModel() },
                onLearnMore = { showEngineInfo = true },
                onRequestDeleteModel = { showDeleteModel = true }
            )

            Spacer(Modifier.height(40.dp))

            SectionHeader("Vocabulary")
            Spacer(Modifier.height(12.dp))
            VocabularyCard(
                words = customWords,
                onEdit = { showVocabEditor = true }
            )

            Spacer(Modifier.height(40.dp))

            SectionHeader("Gestures")
            Spacer(Modifier.height(12.dp))
            ShakeToStopCard(
                enabled = shakeToStop,
                onToggle = { setShakeToStop(it) }
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
                    "You'll switch to the Cloud option until you download it again."
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
                    Text("Cloud", fontWeight = FontWeight.Bold)
                    Text(
                        "Your recording is sent to Groq's servers and transcribed " +
                            "with OpenAI's Whisper model. Very fast and accurate, but " +
                            "it needs an internet connection and a free Groq API key.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("On-device", fontWeight = FontWeight.Bold)
                    Text(
                        "Transcription runs entirely on your phone using NVIDIA's " +
                            "Parakeet model. It works offline and your audio never " +
                            "leaves the device, but it's a bit slower, English-only, " +
                            "and needs a one-time ~630 MB model download.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("LLM Enhanced", fontWeight = FontWeight.Bold)
                    Text(
                        "An optional extra step for Cloud mode. After your speech is " +
                            "transcribed, the text is passed through a small cloud " +
                            "language model that smooths out the wording, tidying " +
                            "phrasing and punctuation so it reads more naturally " +
                            "before it's typed out. It keeps what you meant, just " +
                            "polished. Needs an internet connection and adds a moment " +
                            "of delay.",
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
 * Cloud-only toggle for the post-transcription AI cleanup pass. Sits under the
 * saved-key row because it's an enhancement to Cloud dictation, not a separate
 * engine. Off by default; flipping it just writes the pref the IME reads.
 */
@Composable
private fun RefineRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val haptic = rememberTapHaptic()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { haptic(); onToggle(!enabled) }
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("LLM Enhanced", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                "Cleans up dictated text.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = { haptic(); onToggle(it) }
        )
    }
}

/**
 * Toggle for stopping a recording by shaking the phone, as an alternative to
 * tapping Stop. Off by default; flipping it just writes the pref the IME reads
 * the next time the keyboard appears. Engine-agnostic, so it gets its own card.
 */
@Composable
private fun ShakeToStopCard(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val haptic = rememberTapHaptic()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { haptic(); onToggle(!enabled) }
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Shake to stop", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text(
                    "Once you've started speaking, a quick flick of the phone stops and transcribes — like tapping Stop.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { haptic(); onToggle(it) }
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
                    "add a key again to use Cloud transcription."
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
private fun StepBadge(done: Boolean) {
    val base = Modifier.size(20.dp).clip(CircleShape)
    val styled = if (done) {
        base.background(successColor)
    } else {
        base.border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
    }
    Box(modifier = styled, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = Icons.Rounded.Check,
            contentDescription = if (done) "Done" else "Not done",
            tint = if (done) onSuccessColor
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.size(12.dp)
        )
    }
}

@Composable
private fun StepRow(
    label: String,
    done: Boolean,
    actionLabel: String,
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
            // When done, the checkmark says it all — no "Granted/Enabled" subtext.
            if (!done) {
                Text(
                    "Required",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        if (!done) {
            Spacer(Modifier.width(12.dp))
            Button(onClick = { haptic(); onAction() }) { Text(actionLabel) }
        }
    }
}

@Composable
private fun TranscriptionCard(
    engine: Engine,
    apiKey: String,
    modelReady: Boolean,
    modelProgress: ParakeetModelManager.Progress?,
    refineEnabled: Boolean,
    onToggleRefine: (Boolean) -> Unit,
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
            label = "Cloud",
            subtitle = "Fast, but needs internet",
            selected = engine == Engine.CLOUD,
            onSelect = { haptic(); onSelectEngine(Engine.CLOUD) }
        ) {
            // The presence of the button is implicit enough — no "required" text.
            if (apiKey.isBlank()) {
                Button(onClick = { haptic(); onAddKey() }) { Text("Add API key") }
            } else {
                SavedKeyRow(onRequestEdit = onAddKey, onRequestRemove = onRequestRemoveKey)
                Spacer(Modifier.height(8.dp))
                // Separator so the refine toggle reads as an added layer on top
                // of cloud transcription, not part of the API-key setup above.
                HorizontalDivider()
                RefineRow(enabled = refineEnabled, onToggle = onToggleRefine)
            }
        }
        HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
        EngineOption(
            label = "On-device",
            subtitle = "A bit slower, but works offline",
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            // RadioButton reserves a 48dp touch target (≈14dp inset around the
            // dot), so pull the row's left padding in to line the dot up with the
            // device-setup badges at 16dp rather than floating ~14dp further right.
            .padding(start = 4.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontWeight = FontWeight.Medium)
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

private fun isImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    val target = ComponentName(context, MutterboardInputMethodService::class.java)
    return imm.enabledInputMethodList.any { it.component == target }
}
