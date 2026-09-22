@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.forgeflow.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private val Accent = Color(0xFF10A37F)

@Composable
fun ForgeFlowApp(vm: AgentViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val drawerState = rememberDrawerState(if (ui.drawerOpen) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(ui.drawerOpen) { if (ui.drawerOpen) drawerState.open() else drawerState.close() }
    LaunchedEffect(drawerState.currentValue) { if (drawerState.isClosed && ui.drawerOpen) vm.openDrawer(false) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = Color(0xFF171719), background = Color(0xFF111113))) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = { HistoryDrawer(ui, vm) }
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Workspace(ui, vm) { scope.launch { vm.openDrawer(true) } }
            }
        }
        if (ui.settingsOpen) BackendSettings(ui.backendUrl, ui.deviceToken, ui.error, onDismiss = { vm.openSettings(false) }, onSave = vm::saveBackend)
    }
}

@Composable
private fun Workspace(ui: AppUiState, vm: AgentViewModel, openHistory: () -> Unit) {
    val chat = ui.active
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        TopAppBar(
            title = {
                Column {
                    Text(chat?.title ?: "ForgeFlow", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    if (chat?.id in ui.runningIds) Text("Nemotron работает…", color = Accent, style = MaterialTheme.typography.labelSmall)
                }
            },
            navigationIcon = { IconButton(onClick = openHistory) { Icon(Icons.Default.Menu, "История") } },
            actions = {
                IconButton(onClick = { vm.newChat(ui.mode) }) { Icon(Icons.Default.Edit, "Новый чат") }
                IconButton(onClick = { vm.openSettings(true) }) { Icon(Icons.Default.MoreVert, "Настройки") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
        ModeSelector(ui.mode, vm::switchMode)
        if (chat != null) ConversationBody(
            chat = chat,
            running = chat.id in ui.runningIds,
            modifier = Modifier.weight(1f),
            onDownload = { vm.downloadArtifact(it, false) },
            onShare = { vm.downloadArtifact(it, true) }
        )
        Composer(ui, vm)
    }
}

@Composable
private fun ModeSelector(mode: WorkspaceMode, onMode: (WorkspaceMode) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
        WorkspaceMode.entries.forEachIndexed { index, item ->
            SegmentedButton(
                selected = mode == item,
                onClick = { onMode(item) },
                shape = SegmentedButtonDefaults.itemShape(index, WorkspaceMode.entries.size),
                label = { Text(if (item == WorkspaceMode.CHAT) "Чат" else "Работа") },
                icon = { Icon(if (item == WorkspaceMode.CHAT) Icons.Default.ChatBubbleOutline else Icons.Default.Build, null, Modifier.size(17.dp)) }
            )
        }
    }
}

@Composable
private fun ConversationBody(
    chat: Conversation,
    running: Boolean,
    modifier: Modifier,
    onDownload: (WorkArtifact) -> Unit,
    onShare: (WorkArtifact) -> Unit
) {
    val listState = rememberLazyListState()
    val count = chat.messages.size + if (chat.todos.isNotEmpty()) 1 else 0
    LaunchedEffect(count, running) { if (count > 0) listState.animateScrollToItem(count - 1) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        if (chat.messages.isEmpty()) item { EmptyState(chat.mode) }
        items(chat.messages) { MessageBubble(it) }
        if (chat.mode == WorkspaceMode.WORK && chat.todos.isNotEmpty()) item { TodoCard(chat.todos) }
        chat.artifact?.let { artifact ->
            item { ArtifactCard(artifact, onDownload = { onDownload(artifact) }, onShare = { onShare(artifact) }) }
        }
        if (running) item { TypingIndicator() }
    }
}

@Composable
private fun EmptyState(mode: WorkspaceMode) {
    Box(Modifier.fillMaxSize().padding(top = 96.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 48.dp)) {
            Surface(shape = CircleShape, color = Accent.copy(alpha = .14f)) { Icon(if (mode == WorkspaceMode.CHAT) Icons.Default.AutoAwesome else Icons.Default.Terminal, null, tint = Accent, modifier = Modifier.padding(18.dp).size(28.dp)) }
            Spacer(Modifier.height(16.dp))
            Text(if (mode == WorkspaceMode.CHAT) "Чем помочь?" else "Что нужно создать?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(if (mode == WorkspaceMode.CHAT) "Контекст сохраняется отдельно для каждого чата" else "Сначала появится план, затем — выполнение", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val user = message.role == MessageRole.USER
    val clipboard = LocalClipboardManager.current
    var thinkingOpen by remember(message.createdAt) { mutableStateOf(message.text.isBlank()) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (!user && message.thinking != null) {
            Surface(onClick = { thinkingOpen = !thinkingOpen }, color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (message.text.isBlank()) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp, color = Accent) else Icon(Icons.Default.AutoAwesome, null, Modifier.size(16.dp), tint = Accent)
                    Spacer(Modifier.width(7.dp)); Text(if (message.text.isBlank()) "Думаю…" else "Ход работы", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (thinkingOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                }
            }
            AnimatedVisibility(thinkingOpen) { Text(message.thinking, modifier = Modifier.padding(start = 4.dp, bottom = 5.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton(
            onClick = { clipboard.setText(AnnotatedString(message.text)) },
            modifier = Modifier.size(30.dp)
        ) { Icon(Icons.Default.ContentCopy, "Копировать", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (message.attachments.isNotEmpty()) Column(Modifier.widthIn(max = 340.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            message.attachments.forEach { file ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp), color = Accent.copy(alpha=.14f)) { Icon(if(file.name.endsWith(".mtz",true)) Icons.Default.Palette else Icons.Default.InsertDriveFile, null, tint=Accent, modifier=Modifier.padding(9.dp).size(20.dp)) }
                        Spacer(Modifier.width(10.dp));Column { Text(file.name, fontWeight=FontWeight.Medium, maxLines=1, overflow=TextOverflow.Ellipsis);Text(if(file.name.endsWith(".mtz",true)) "Тема HyperOS · содержимое проанализировано" else file.mime, style=MaterialTheme.typography.labelSmall, color=MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
        Surface(
            shape = RoundedCornerShape(if (user) 22.dp else 10.dp),
            color = if (user) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
            modifier = Modifier.widthIn(max = 340.dp).animateContentSize()
        ) {
            MarkdownText(message.text, Modifier.padding(if (user) 14.dp else 4.dp))
        }
    }
}

@Composable
private fun MarkdownText(source:String, modifier:Modifier=Modifier){
    val parts=source.split("```")
    Column(modifier, verticalArrangement=Arrangement.spacedBy(8.dp)){
        parts.forEachIndexed{index,part->if(index%2==1){Surface(color=Color(0xFF202124),shape=RoundedCornerShape(10.dp),modifier=Modifier.fillMaxWidth()){SelectionContainer{Text(part.trim().substringAfter('\n',part.trim()),Modifier.padding(12.dp),fontFamily=FontFamily.Monospace,style=MaterialTheme.typography.bodySmall)}}}else if(part.isNotEmpty()){val styled=buildAnnotatedString{var cursor=0;val regex=Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`");regex.findAll(part).forEach{m->append(part.substring(cursor,m.range.first));if(m.groupValues[1].isNotEmpty())pushStyle(SpanStyle(fontWeight=FontWeight.Bold))else pushStyle(SpanStyle(fontFamily=FontFamily.Monospace,background=MaterialTheme.colorScheme.surfaceVariant));append(m.groupValues[1].ifEmpty{m.groupValues[2]});pop();cursor=m.range.last+1};append(part.substring(cursor))};SelectionContainer{Text(styled,style=MaterialTheme.typography.bodyLarge)}}}
    }
}

@Composable
private fun TodoCard(todos: List<WorkTodo>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Checklist, null, tint = Accent)
                Spacer(Modifier.width(10.dp))
                Text("План работы", fontWeight = FontWeight.SemiBold)
            }
            todos.forEach { todo ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when (todo.state) {
                        TodoState.RUNNING -> CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                        TodoState.DONE -> Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF4CAF78), modifier = Modifier.size(19.dp))
                        TodoState.FAILED -> Icon(Icons.Default.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(19.dp))
                        TodoState.WAITING -> Icon(Icons.Default.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(todo.title, color = if (todo.state == TodoState.DONE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface, textDecoration = if (todo.state == TodoState.DONE) TextDecoration.LineThrough else null)
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
        Spacer(Modifier.width(10.dp))
        Text("Обрабатываю запрос…", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ArtifactCard(artifact: WorkArtifact, onDownload: () -> Unit, onShare: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Accent.copy(alpha = .12f)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null, tint = Accent)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(artifact.name, fontWeight = FontWeight.SemiBold)
                    Text("Готовый artifact · ${"%.1f".format(artifact.size / 1048576.0)} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDownload) { Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("Download") }
                OutlinedButton(onClick = onShare) { Icon(Icons.Default.Share, null); Spacer(Modifier.width(6.dp)); Text("Share") }
            }
        }
    }
}

@Composable
private fun Composer(ui: AppUiState, vm: AgentViewModel) {
    val running = ui.activeId in ui.runningIds
    var modelsOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.addAttachments(uris) }
    Surface(color = MaterialTheme.colorScheme.background, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { modelsOpen = true }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Icon(Icons.Default.Memory, null, Modifier.size(16.dp)); Spacer(Modifier.width(5.dp))
                        Text(if (ui.selectedModel == MODEL_ULTRA) "Nemotron Ultra 550B" else "Nemotron Super 120B", style = MaterialTheme.typography.labelMedium)
                        Icon(Icons.Default.ArrowDropDown, null, Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = modelsOpen, onDismissRequest = { modelsOpen = false }) {
                        DropdownMenuItem(text = { Text("Nemotron Super 120B") }, leadingIcon = { if (ui.selectedModel == MODEL_SUPER) Icon(Icons.Default.Check, null) }, onClick = { vm.selectModel(MODEL_SUPER); modelsOpen = false })
                        DropdownMenuItem(text = { Text("Nemotron Ultra 550B") }, leadingIcon = { if (ui.selectedModel == MODEL_ULTRA) Icon(Icons.Default.Check, null) }, onClick = { vm.selectModel(MODEL_ULTRA); modelsOpen = false })
                    }
                }
            }
            if (ui.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.attachments.take(3).forEach { file -> InputChip(selected = true, onClick = { vm.removeAttachment(file.name) }, label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, trailingIcon = { Icon(Icons.Default.Close, "Убрать", Modifier.size(15.dp)) }, modifier = Modifier.widthIn(max = 150.dp)) }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }) { Icon(Icons.Default.Add, "Добавить файл") }
                OutlinedTextField(
                    value = ui.input,
                    onValueChange = vm::setInput,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text(if (ui.mode == WorkspaceMode.CHAT) "Сообщение…" else "Опишите задачу…") },
                    shape = RoundedCornerShape(26.dp),
                    maxLines = 5,
                    trailingIcon = {
                        FilledIconButton(onClick = { if (running) vm.stop() else vm.send() }, enabled = running || ui.input.isNotBlank(), colors = IconButtonDefaults.filledIconButtonColors(containerColor = if (running || ui.input.isNotBlank()) Accent else MaterialTheme.colorScheme.surfaceVariant)) {
                            Icon(if (running) Icons.Default.Stop else Icons.Default.ArrowUpward, if (running) "Остановить" else "Отправить")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun HistoryDrawer(ui: AppUiState, vm: AgentViewModel) {
    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Spacer(Modifier.statusBarsPadding())
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("ForgeFlow", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.newChat() }) { Icon(Icons.Default.Add, "Новый чат") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(ui.conversations.sortedByDescending { it.updatedAt }, key = { it.id }) { chat ->
                NavigationDrawerItem(
                    selected = chat.id == ui.activeId,
                    onClick = { vm.selectChat(chat.id) },
                    icon = { Icon(if (chat.mode == WorkspaceMode.WORK) Icons.Default.Build else Icons.Default.ChatBubbleOutline, null) },
                    label = {
                        Column {
                            Text(chat.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (chat.mode == WorkspaceMode.WORK) "Работа" else "Чат", style = MaterialTheme.typography.labelSmall, color = if (chat.mode == WorkspaceMode.WORK) Accent else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    badge = { IconButton(onClick = { vm.deleteChat(chat.id) }, Modifier.size(32.dp)) { Icon(Icons.Default.Close, "Удалить", Modifier.size(16.dp)) } }
                )
            }
        }
        Text("Nemotron 3 Super · локальная история", modifier = Modifier.padding(18.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun BackendSettings(initialUrl: String, initialToken: String, error: String?, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text("WorkAI Backend") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("NVIDIA и GitHub ключи хранятся на сервере. В приложение вводится только персональный токен устройства.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, label = { Text("Backend URL") }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text("WORKAI_DEVICE_TOKEN") }, singleLine = true)
                AnimatedVisibility(error != null) { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url, token) }, enabled = url.isNotBlank() && token.length >= 20) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
