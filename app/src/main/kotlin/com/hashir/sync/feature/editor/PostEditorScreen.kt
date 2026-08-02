package com.hashir.sync.feature.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostEditorScreen(
    onBack: () -> Unit,
    onPublished: () -> Unit,
    viewModel: PostEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showScheduler by remember { mutableStateOf(false) }

    if (showScheduler) {
        SchedulePickerDialog(
            onDismiss = { showScheduler = false },
            onPicked = { millis ->
                showScheduler = false
                viewModel.setSchedule(millis)
            },
        )
    }

    // Photo Picker rather than a storage permission. It runs out of process and grants
    // access only to what the user selected, so the app never asks for media permissions
    // at all -- nothing to deny, nothing to explain.
    val pickMedia = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = viewModel::onMediaPicked,
    )

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        viewModel.publishQueued.collect { onPublished() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (state.mode == EditorMode.TEXT) "New post" else "New media post")
                        if (state.pageName.isNotBlank()) {
                            Text(
                                text = "to ${state.pageName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            if (state.mode == EditorMode.MEDIA) {
                MediaSection(
                    state = state,
                    onPick = {
                        pickMedia.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo,
                            ),
                        )
                    },
                    onClear = viewModel::clearMedia,
                )
                Spacer(Modifier.height(16.dp))
            }

            OutlinedTextField(
                value = state.caption,
                onValueChange = viewModel::onCaptionChanged,
                label = { Text("Caption") },
                placeholder = { Text("What do you want to say?") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
                supportingText = { Text("${state.caption.length} characters") },
                enabled = !state.isPublishing,
            )

            if (state.mode == EditorMode.TEXT) {
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.link,
                    onValueChange = viewModel::onLinkChanged,
                    label = { Text("Link (optional)") },
                    placeholder = { Text("https://...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isPublishing,
                )
            }

            Spacer(Modifier.height(16.dp))

            ScheduleRow(
                scheduledFor = state.scheduledFor,
                enabled = !state.isPublishing,
                onPick = { showScheduler = true },
                onClear = viewModel::clearSchedule,
            )

            // Surfaced before publishing rather than after: hitting Facebook's daily cap
            // returns an opaque rate-limit error that looks like a bug.
            state.videoQuotaRemaining?.takeIf { state.isVideo }?.let { remaining ->
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (remaining <= 0) {
                            MaterialTheme.colorScheme.errorContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = when {
                            remaining <= 0 ->
                                "This Page has used all 30 API video posts for today. " +
                                    "Try again tomorrow."

                            remaining <= 5 ->
                                "$remaining of 30 video posts left for this Page today."

                            else -> "$remaining video posts left for this Page today."
                        },
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = viewModel::publish,
                enabled = state.canPublish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                if (state.isPublishing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Queueing...")
                } else {
                    Icon(
                        if (state.isScheduled) Icons.Default.Schedule else Icons.Default.Send,
                        contentDescription = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(if (state.isScheduled) "Schedule" else "Publish")
                }
            }
        }
    }
}

@Composable
private fun ScheduleRow(
    scheduledFor: Long?,
    enabled: Boolean,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Schedule,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = if (scheduledFor == null) "Publish now" else "Scheduled",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (scheduledFor != null) {
                Text(
                    text = SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
                        .format(Date(scheduledFor)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (scheduledFor != null) {
            TextButton(onClick = onClear, enabled = enabled) { Text("Clear") }
        }
        TextButton(onClick = onPick, enabled = enabled) {
            Text(if (scheduledFor == null) "Schedule" else "Change")
        }
    }
}

/**
 * Date then time, because Material 3 has no combined picker.
 *
 * The date picker refuses anything before today: Facebook rejects a schedule in the past,
 * and the resulting error names neither the field nor the reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulePickerDialog(
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit,
) {
    var pickedDateMillis by remember { mutableStateOf<Long?>(null) }

    if (pickedDateMillis == null) {
        val dateState = rememberDatePickerState(
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean =
                    utcTimeMillis >= System.currentTimeMillis() - DAY_MS
            },
        )
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(
                    onClick = { pickedDateMillis = dateState.selectedDateMillis },
                    enabled = dateState.selectedDateMillis != null,
                ) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        ) {
            DatePicker(state = dateState)
        }
    } else {
        val timeState = rememberTimePickerState(is24Hour = true)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Pick a time") },
            text = { TimePicker(state = timeState) },
            confirmButton = {
                TextButton(
                    onClick = {
                        // The date picker returns UTC midnight; the time is local. Combined
                        // through a local Calendar so the result is the wall-clock time the
                        // user actually chose.
                        val calendar = Calendar.getInstance().apply {
                            timeInMillis = pickedDateMillis!!
                            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                                timeInMillis = pickedDateMillis!!
                            }
                            set(Calendar.YEAR, utc.get(Calendar.YEAR))
                            set(Calendar.MONTH, utc.get(Calendar.MONTH))
                            set(Calendar.DAY_OF_MONTH, utc.get(Calendar.DAY_OF_MONTH))
                            set(Calendar.HOUR_OF_DAY, timeState.hour)
                            set(Calendar.MINUTE, timeState.minute)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }
                        onPicked(calendar.timeInMillis)
                    },
                ) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        )
    }
}

private const val DAY_MS = 24 * 60 * 60 * 1000L

@Composable
private fun MediaSection(
    state: PostEditorUiState,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    if (!state.hasMedia) {
        Card(
            onClick = onPick,
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.AddPhotoAlternate,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text("Choose a photo or video", style = MaterialTheme.typography.titleMedium)
                Text(
                    "No storage permission needed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        val previewModifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !state.isPublishing, onClick = onPick)

        if (state.isVideo) {
            // Coil cannot decode a video frame without the extra coil-video artifact and
            // a custom ImageLoader. A clear placeholder is better than an empty box, and
            // the filename below identifies which clip was picked.
            Box(previewModifier, contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Movie,
                    contentDescription = "Selected video",
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            AsyncImage(
                model = state.mediaUri,
                contentDescription = "Selected media",
                contentScale = ContentScale.Crop,
                modifier = previewModifier,
            )
        }
        IconButton(
            onClick = onClear,
            enabled = !state.isPublishing,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }

    Row(
        modifier = Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listOfNotNull(state.mediaName, state.mediaSizeLabel).joinToString("  -  "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.isVideo) {
        Spacer(Modifier.height(12.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Info, contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    // No video/reel choice is offered on purpose: Facebook merged the two,
                    // and publishing picks the endpoint itself, falling back if refused.
                    text = "Videos upload in the background and can be resumed if the " +
                        "connection drops. Progress appears in the queue.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
