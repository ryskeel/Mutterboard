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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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

// Vocabulary list collapses to this many rows of badges; a "Show more" toggle
// reveals the rest. Keeps a long word list from dominating the settings screen.
private const val VOCAB_COLLAPSED_ROWS = 3
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

    var customWords by remember {
        mutableStateOf(
            MutterboardInputMethodService.parseCustomWords(
                prefs.getString(MutterboardInputMethodService.KEY_CUSTOM_WORDS, null)
            )
        )
    }
    var showAddWord by remember { mutableStateOf(false) }

    fun saveCustomWords(words: List<String>) {
        customWords = words
        prefs.edit()
            .putString(MutterboardInputMethodService.KEY_CUSTOM_WORDS, words.joinToString("\n"))
            .apply()
    }

    fun addCustomWord(word: String) {
        val cleaned = word.trim()
        if (cleaned.isEmpty()) return
        // Case-insensitive de-dupe so "Mutterboard" and "mutterboard" don't both stick.
        if (customWords.any { it.equals(cleaned, ignoreCase = true) }) return
        saveCustomWords(customWords + cleaned)
    }

    fun removeCustomWord(word: String) {
        saveCustomWords(customWords.filterNot { it == word })
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
                onAdd = { showAddWord = true },
                onRemove = { removeCustomWord(it) }
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

    if (showAddWord) {
        AddWordDialog(
            onDismiss = { showAddWord = false },
            onAdd = { word ->
                addCustomWord(word)
                showAddWord = false
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
            Text("Polish my text", fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(
                "Fixes small slips so messages come out cleaner. Slightly slower.",
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
 * Custom vocabulary editor. The word list applies to both engines (it's fed to
 * Whisper as a prompt on Cloud and fuzzy-matched against output on-device), so it
 * lives in its own section rather than under either engine. Added words show as
 * peach badges (matching the saved-key / model-ready rows); tapping the × removes.
 */
// FlowRow's overflow param (and the expandIndicator factory) are deprecated in
// foundation 1.10 — the maintained replacement is maxLines + a hand-rolled
// indicator, but that needs SubcomposeLayout-level overflow measurement. The
// overflow API still works on the pinned BOM and reads cleanly, so we keep it
// and revisit if a Compose upgrade removes it.
@OptIn(ExperimentalLayoutApi::class)
@Suppress("DEPRECATION")
@Composable
private fun VocabularyCard(
    words: List<String>,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    val haptic = rememberTapHaptic()
    // Collapsed by default and reset on each entry into the screen (plain remember,
    // so reopening the app starts collapsed again) to keep a long word list compact.
    var expanded by remember { mutableStateOf(false) }
    // Whether the collapsed list ever actually overflowed VOCAB_COLLAPSED_ROWS. We
    // only know this when the expand indicator composes (it only renders on real
    // overflow), so latch it. Without this gate, the FlowRow shows "Show less"
    // whenever expanded — even when the whole list fits and there's nothing to
    // collapse, making the toggle look broken.
    var overflowed by remember { mutableStateOf(false) }
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
            if (words.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    maxLines = if (expanded) Int.MAX_VALUE else VOCAB_COLLAPSED_ROWS,
                    // Only the expand side is driven by FlowRow overflow: this
                    // indicator composes solely when the list exceeds the row cap,
                    // so it both shows "Show more" and latches that there's really
                    // something to collapse. "Show less" is rendered separately
                    // below, gated on that latch, so it never appears when the
                    // whole list already fits.
                    overflow = FlowRowOverflow.expandIndicator {
                        SideEffect { overflowed = true }
                        VocabExpandToggle("Show more") { haptic(); expanded = true }
                    }
                ) {
                    words.forEach { word ->
                        WordBadge(word = word, onRemove = { haptic(); onRemove(word) })
                    }
                }
                if (expanded && overflowed) {
                    VocabExpandToggle("Show less") { haptic(); expanded = false }
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = { haptic(); onAdd() }) { Text("Add word") }
        }
    }
}

/**
 * The "Show more" / "Show less" control that sits inline at the end of the
 * vocabulary badges, styled as a text link rather than a badge so it reads as an
 * action. Shown by [VocabularyCard]'s FlowRow overflow only when the list
 * actually exceeds [VOCAB_COLLAPSED_ROWS].
 */
@Composable
private fun VocabExpandToggle(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
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

@Composable
private fun AddWordDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val haptic = rememberTapHaptic()
    var draft by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    // Open straight into a focused, keyboard-up state so adding a word is a single
    // tap-then-type instead of tap-then-tap-the-field.
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a word") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
            )
        },
        confirmButton = {
            TextButton(
                onClick = { haptic(); onAdd(draft) },
                enabled = draft.isNotBlank()
            ) { Text("Add") }
        },
        dismissButton = {
            TextButton(onClick = { haptic(); onDismiss() }) { Text("Cancel") }
        }
    )
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
