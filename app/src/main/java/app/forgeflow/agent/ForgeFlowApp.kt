package app.forgeflow.agent

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

private val Ink = Color(0xFF151515)
private val Cloud = Color(0xFFF7F7F5)
private val Lime = Color(0xFFB8F248)

@Composable
fun ForgeFlowApp(vm: AgentViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val scheme = if (dark) darkColorScheme(primary = Lime, background = Color(0xFF0F0F10), surface = Color(0xFF19191B))
    else lightColorScheme(primary = Ink, background = Cloud, surface = Color.White)
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize()) { TaskScreen(ui, vm::updatePrompt, vm::attach, vm::run) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskScreen(ui: TaskUi, onPrompt: (String) -> Unit, onAttach: (String) -> Unit, onRun: () -> Unit) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            onAttach(uri.lastPathSegment?.substringAfterLast('/') ?: "attachment")
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ForgeFlow", fontWeight = FontWeight.SemiBold)
                    Text("AI workspace", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }},
                navigationIcon = { IconButton({}) { Icon(Icons.Outlined.Menu, "History") } },
                actions = { IconButton({}) { Icon(Icons.Outlined.Settings, "Settings") } }
            )
        },
        bottomBar = {
            Composer(ui, onPrompt, { picker.launch(arrayOf("*/*")) }, onRun)
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { IntroCard() }
            if (ui.prompt.isNotBlank() && ui.state != AgentState.IDLE) item { UserPrompt(ui.prompt) }
            if (ui.attachedNames.isNotEmpty()) item { AttachmentRow(ui.attachedNames) }
            if (ui.steps.isNotEmpty()) item { ActivityCard(ui) }
            items(ui.artifacts) { ArtifactCard(it) }
        }
    }
}

@Composable private fun IntroCard() {
    Column(Modifier.fillMaxWidth().padding(top = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(58.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(18.dp))
        Text("What should I build?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text("Describe a file or app. ForgeFlow will plan, work, build and verify it.", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun UserPrompt(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(22.dp, 22.dp, 5.dp, 22.dp)) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp).widthIn(max = 300.dp))
        }
    }
}

@Composable private fun AttachmentRow(names: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { names.forEach { name ->
        Surface(shape = RoundedCornerShape(14.dp), tonalElevation = 1.dp) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.InsertDriveFile, null)
                Spacer(Modifier.width(10.dp)); Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }}
}

@Composable private fun ActivityCard(ui: TaskUi) {
    ElevatedCard(Modifier.fillMaxWidth().animateContentSize(), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            AnimatedContent(ui.state, label = "state") { state ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state != AgentState.COMPLETED && state != AgentState.FAILED) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(if (state == AgentState.COMPLETED) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null, tint = if (state == AgentState.COMPLETED) Color(0xFF2C8B57) else MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(10.dp)); Text(if (state == AgentState.COMPLETED) "Task completed" else "Working on your task…", fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider()
            ui.steps.forEach { step -> StepRow(step) }
        }
    }
}

@Composable private fun StepRow(step: ActivityStep) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(step.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        when (step.state) {
            StepState.RUNNING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            StepState.COMPLETED -> Icon(Icons.Outlined.Check, null, tint = Color(0xFF2C8B57), modifier = Modifier.size(18.dp))
            StepState.FAILED -> Icon(Icons.Outlined.Close, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            StepState.WAITING -> Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.outlineVariant, CircleShape))
        }
    }
}

@Composable private fun ArtifactCard(artifact: Artifact) {
    AnimatedVisibility(true) {
        OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) { Icon(Icons.Outlined.Inventory2, null, Modifier.padding(10.dp)) }
                    Spacer(Modifier.width(12.dp)); Column { Text(artifact.name, fontWeight = FontWeight.SemiBold); Text("${artifact.type} · ${artifact.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(12.dp)); Row { TextButton({}, enabled = artifact.downloadUrl.isNotBlank()) { Icon(Icons.Outlined.Download, null); Spacer(Modifier.width(6.dp)); Text("Download") }; TextButton({}, enabled = artifact.downloadUrl.isNotBlank()) { Icon(Icons.Outlined.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") } }
            }
        }
    }
}

@Composable private fun Composer(ui: TaskUi, onPrompt: (String) -> Unit, onAttach: () -> Unit, onRun: () -> Unit) {
    Surface(shadowElevation = 8.dp) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(10.dp)) {
            Surface(shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f)) {
                Row(Modifier.fillMaxWidth().padding(6.dp), verticalAlignment = Alignment.Bottom) {
                    IconButton(onAttach) { Icon(Icons.Outlined.Add, "Add files") }
                    TextField(ui.prompt, onPrompt, Modifier.weight(1f), placeholder = { Text("Describe what to create…") }, colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent), maxLines = 5)
                    FilledIconButton(onRun, enabled = ui.prompt.isNotBlank() && ui.state !in listOf(AgentState.PLANNING, AgentState.WORKING, AgentState.BUILDING, AgentState.VERIFYING)) { Icon(Icons.Outlined.ArrowUpward, "Send") }
                }
            }
        }
    }
}
