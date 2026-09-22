@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.workai.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.TextView
import android.text.method.LinkMovementMethod
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

private val Accent = Color(0xFF0A84FF)

@Composable
fun WorkAIApp(vm: AgentViewModel = viewModel()) {
    val ui by vm.ui.collectAsState()
    val drawerState = rememberDrawerState(if (ui.drawerOpen) DrawerValue.Open else DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    LaunchedEffect(ui.drawerOpen) { if (ui.drawerOpen) drawerState.open() else drawerState.close() }
    LaunchedEffect(drawerState.currentValue) { if (drawerState.isClosed && ui.drawerOpen) vm.openDrawer(false) }

    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, surface = Color(0xFF242424), surfaceVariant = Color(0xFF303030), background = Color.Black)) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = true,
            drawerContent = { HistoryDrawer(ui, vm) }
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Workspace(ui, vm) { scope.launch { vm.openDrawer(true) } }
            }
        }
        if (ui.settingsOpen) BackendSettings(ui.backendUrl, ui.deviceToken, ui.systemPrompt, ui.error, onDismiss = { vm.openSettings(false) }, onSave = vm::saveBackend)
    }
}

@Composable
private fun Workspace(ui: AppUiState, vm: AgentViewModel, openHistory: () -> Unit) {
    val chat = ui.active
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val openDrawer = { focusManager.clearFocus(); keyboard?.hide(); openHistory() }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        CenterAlignedTopAppBar(
            title = { Text(if(chat?.messages?.isNotEmpty()==true) if(chat.mode==WorkspaceMode.WORK) "Работа" else "Чат" else "Новый чат", style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold) },
            navigationIcon = { FilledIconButton(onClick = openDrawer, colors=IconButtonDefaults.filledIconButtonColors(containerColor=Color(0xFF242424))) { Icon(Icons.Default.Menu, "История") } },
            actions = {
                IconButton(onClick = { vm.newChat(ui.mode) }) { Icon(painterResource(app.workai.agent.R.drawable.ic_square_pen), "Новый чат") }
                IconButton(onClick = { vm.openSettings(true) }) { Icon(Icons.Default.MoreVert, "Настройки") }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
        )
        if(chat?.messages?.isEmpty()!=false) ModeSelector(ui.mode, vm::switchMode)
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
    val bubbleOffset by animateDpAsState(
        targetValue = if (mode == WorkspaceMode.CHAT) 0.dp else 124.dp,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "modeBubble"
    )
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center){ Box(
        Modifier.padding(vertical = 6.dp).width(250.dp).height(40.dp)
            .clip(RoundedCornerShape(20.dp)).background(Color(0xFF101010))
            .border(1.dp, Color(0xFF292929), RoundedCornerShape(20.dp))
    ) {
        Box(
            Modifier.padding(1.dp).offset(x = bubbleOffset).width(124.dp).fillMaxHeight()
                .clip(RoundedCornerShape(19.dp)).background(Color(0xFF1C1C1C))
        )
        Row(Modifier.fillMaxSize()) {
            WorkspaceMode.entries.forEach { item ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clickable { onMode(item) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (item == WorkspaceMode.CHAT) "Чат" else "Работа",
                        color = if (mode == item) Color.White else Color(0xFFE5E5E5),
                        fontWeight = if (mode == item) FontWeight.SemiBold else FontWeight.Medium
                    )
                }
            }
        }
    } }
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
        if (running && chat.messages.lastOrNull()?.role != MessageRole.ASSISTANT) item { TypingIndicator() }
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
        if (!user && message.thinking != null && (message.text.isBlank() || message.thinking.isNotBlank())) {
            Surface(onClick = { thinkingOpen = !thinkingOpen }, color = Color.Transparent, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (message.text.isBlank() && message.thinking.isNullOrBlank()) { Box(Modifier.size(15.dp).clip(CircleShape).background(Accent)); Spacer(Modifier.width(8.dp)) }
                    Text("Размышление", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(if (thinkingOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(18.dp))
                }
            }
            AnimatedVisibility(thinkingOpen && message.thinking.isNotBlank()) {
                Row(Modifier.padding(start=4.dp,bottom=8.dp)){
                    Box(Modifier.width(2.dp).fillMaxHeight().background(Color(0xFF3A3A3A)))
                    Text(message.thinking.orEmpty(), modifier = Modifier.padding(start = 14.dp), style = MaterialTheme.typography.bodyMedium, color = Color(0xFFB7B7B7))
                }
            }
        }
        if(!user && message.activities.isNotEmpty()) Column(Modifier.padding(start=4.dp,bottom=10.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            message.activities.forEach{activity->Row(verticalAlignment=Alignment.CenterVertically){Icon(when(activity.icon){"file"->Icons.Default.FolderOpen;"build"->Icons.Default.Terminal;else->Icons.Default.Language},null,Modifier.size(21.dp),tint=if(activity.icon=="search")Color(0xFF76B900) else Color(0xFFB0B0B0));Spacer(Modifier.width(10.dp));Text(activity.label,color=Color(0xFFB8B8B8),style=MaterialTheme.typography.bodyMedium)}}
        }
        if(message.text.isNotBlank()) IconButton(
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
            if(user) Text(message.text,Modifier.padding(horizontal=14.dp,vertical=10.dp),style=MaterialTheme.typography.bodyLarge)
            else MarkdownText(message.text, Modifier.padding(4.dp))
        }
    }
}

@Composable
private fun MarkdownText(source:String, modifier:Modifier=Modifier){
    val context=LocalContext.current
    val markwon=remember(context){Markwon.builder(context).usePlugin(TablePlugin.create(context)).usePlugin(StrikethroughPlugin.create()).usePlugin(TaskListPlugin.create(context)).build()}
    AndroidView(
        modifier=modifier.fillMaxWidth(),
        factory={TextView(it).apply{setTextColor(android.graphics.Color.WHITE);textSize=17f;setTextIsSelectable(true);movementMethod=LinkMovementMethod.getInstance();setLineSpacing(0f,1.13f)}},
        update={markwon.setMarkdown(it,source)}
    )
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
    Box(Modifier.size(18.dp).clip(CircleShape).background(Accent))
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
    var reasoningOpen by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> vm.addAttachments(uris) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 12.dp, vertical = 8.dp).navigationBarsPadding()) {
            if(ui.error!=null) Text(ui.error,Modifier.padding(horizontal=12.dp,vertical=4.dp),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.labelSmall)
            if (ui.attachments.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ui.attachments.take(3).forEach { file -> InputChip(selected = true, onClick = { vm.removeAttachment(file.name) }, label = { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, trailingIcon = { Icon(Icons.Default.Close, "Убрать", Modifier.size(15.dp)) }, modifier = Modifier.widthIn(max = 150.dp)) }
            }
            Surface(shape=RoundedCornerShape(28.dp),color=Color(0xFF242424),modifier=Modifier.fillMaxWidth()){
                Column(Modifier.padding(horizontal=8.dp,vertical=5.dp)){
                    androidx.compose.foundation.text.BasicTextField(value=ui.input,onValueChange=vm::setInput,modifier=Modifier.fillMaxWidth().heightIn(min=42.dp,max=130.dp).padding(horizontal=10.dp,vertical=10.dp),textStyle=MaterialTheme.typography.bodyLarge.copy(color=Color.White),cursorBrush=androidx.compose.ui.graphics.SolidColor(Accent),decorationBox={inner->Box{if(ui.input.isEmpty())Text(if(ui.mode==WorkspaceMode.CHAT)"Сообщение…" else "Опишите задачу…",color=Color(0xFF9B9B9B));inner()}})
                    Row(verticalAlignment=Alignment.CenterVertically){
                        IconButton(onClick={picker.launch("*/*")},modifier=Modifier.size(42.dp)){Icon(Icons.Default.Add,"Добавить файл")}
                        Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable{reasoningOpen=true}.padding(horizontal=8.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
                            Text("${modelShort(ui.selectedModel)} ${reasoningLabel(ui.reasoningEffort)}",color=Color.White,fontWeight=FontWeight.SemiBold,style=MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Default.KeyboardArrowDown,null,Modifier.size(17.dp),tint=Accent)
                        }
                        Spacer(Modifier.weight(1f))
                        val canSend=running||ui.input.isNotBlank()||ui.attachments.isNotEmpty()
                        Surface(shape=CircleShape,color=if(canSend)Accent else Color(0xFF3B3B3B),modifier=Modifier.size(40.dp).pointerInput(canSend,running){detectTapGestures(onTap={if(canSend){if(running)vm.stop() else vm.send()}},onLongPress={if(!running)reasoningOpen=true})}){Box(contentAlignment=Alignment.Center){Icon(if(running)Icons.Default.Stop else Icons.Default.ArrowUpward,if(running)"Остановить" else "Отправить",tint=if(canSend)Color.White else Color(0xFF8A8A8A))}}
                    }
                }
            }
        }
    }
    if(reasoningOpen) ReasoningPopup(ui,onDismiss={reasoningOpen=false},onModels={reasoningOpen=false;modelsOpen=true},onSelect={vm.selectReasoning(it)})
    if(modelsOpen) ModalBottomSheet(onDismissRequest={modelsOpen=false},containerColor=Color(0xFF202020)){
        Text("Настройка",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold,modifier=Modifier.align(Alignment.CenterHorizontally).padding(vertical=8.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal=28.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.Center){
            Text("${modelShort(ui.selectedModel)} ${reasoningLabel(ui.reasoningEffort)}",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Spacer(Modifier.width(6.dp));Icon(Icons.Default.KeyboardArrowRight,null,tint=Color(0xFFBDBDBD))
        }
        IntelligenceSlider(ui,vm::selectReasoning)
        Text("Модель",style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(start=28.dp,bottom=10.dp))
        Column(Modifier.padding(horizontal=20.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xFF414141))){
            listOf(MODEL_SUPER,MODEL_ULTRA,MODEL_AGNES_25,MODEL_AGNES_30).forEach{model->
                ModelRow(modelTitle(model),modelSubtitle(model),ui.selectedModel==model){vm.selectModel(model)}
                if(model!=MODEL_AGNES_30)HorizontalDivider(color=Color(0xFF252525))
            }
        }
        Button(onClick={modelsOpen=false},modifier=Modifier.fillMaxWidth().padding(horizontal=28.dp),colors=ButtonDefaults.buttonColors(containerColor=Color.White,contentColor=Color.Black)){Text("Готово",fontWeight=FontWeight.Bold)}
        Spacer(Modifier.navigationBarsPadding().height(16.dp))
    }
}

private fun modelShort(model:String)=when(model){MODEL_ULTRA->"Ultra";MODEL_AGNES_25->"Agnes 2.5";MODEL_AGNES_30->"Agnes 3.0";else->"Super"}
private fun modelTitle(model:String)=when(model){MODEL_ULTRA->"Nemotron Ultra 550B";MODEL_AGNES_25->"Agnes 2.5 Flash";MODEL_AGNES_30->"Agnes 3.0 Flash";else->"Nemotron Super 120B"}
private fun modelSubtitle(model:String)=when(model){MODEL_ULTRA->"Максимальное качество NVIDIA";MODEL_AGNES_25->"Быстрая агентная модель · 512K";MODEL_AGNES_30->"Новая модель Agnes · доступ зависит от API";else->"Быстро и экономно"}

private fun reasoningLabel(value:String)=when(value){"none"->"Без размышления";"low"->"Низкий";"medium"->"Средний";else->"Высокий"}

@Composable private fun IntelligenceSlider(ui:AppUiState,onSelect:(String)->Unit){
    val values=if(ui.selectedModel==MODEL_ULTRA)listOf("none","medium","high") else if(ui.selectedModel.startsWith("agnes-"))listOf("none","low","medium","high") else listOf("none","low","high")
    Column(Modifier.padding(horizontal=28.dp,vertical=18.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){values.forEach{Text(reasoningLabel(it),color=if(it==ui.reasoningEffort)Color.White else Color(0xFF8A8A8A),style=MaterialTheme.typography.labelMedium)}}
        Spacer(Modifier.height(14.dp))
        Surface(shape=RoundedCornerShape(40.dp),color=Color(0xFF292929),border=androidx.compose.foundation.BorderStroke(1.dp,Color(0xFF4A4A4A))){Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween){values.forEach{value->Box(Modifier.size(46.dp).clip(CircleShape).background(if(value==ui.reasoningEffort)Accent else Color.Transparent).clickable{onSelect(value)},contentAlignment=Alignment.Center){Box(Modifier.size(if(value==ui.reasoningEffort)18.dp else 12.dp).clip(CircleShape).background(if(value==ui.reasoningEffort)Color.White else Color(0xFF6C6C6C)))}}}}
    }
}

@Composable private fun ReasoningPopup(ui:AppUiState,onDismiss:()->Unit,onModels:()->Unit,onSelect:(String)->Unit){
    ModalBottomSheet(onDismissRequest=onDismiss,containerColor=Color(0xFF202020),scrimColor=Color.Black.copy(alpha=.78f)){
        Row(Modifier.fillMaxWidth().clickable(onClick=onModels).padding(vertical=14.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){
            Text("${modelShort(ui.selectedModel)} ${reasoningLabel(ui.reasoningEffort)}",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Spacer(Modifier.width(8.dp));Icon(Icons.Default.KeyboardArrowRight,null,tint=Color(0xFFBDBDBD))
        }
        IntelligenceSlider(ui,onSelect)
        Spacer(Modifier.navigationBarsPadding().height(18.dp))
    }
}

@Composable private fun ModelRow(title:String,subtitle:String,selected:Boolean,onClick:()->Unit){
    Row(Modifier.fillMaxWidth().clickable(onClick=onClick).padding(horizontal=24.dp,vertical=17.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium);Text(subtitle,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)};if(selected)Icon(Icons.Default.Check,null)}
}

@Composable
private fun HistoryDrawer(ui: AppUiState, vm: AgentViewModel) {
    ModalDrawerSheet(modifier = Modifier.width(320.dp)) {
        Spacer(Modifier.statusBarsPadding())
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("WorkAI", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.newChat() }) { Icon(painterResource(app.workai.agent.R.drawable.ic_square_pen), "Новый чат") }
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
        NavigationDrawerItem(selected=false,onClick={vm.openSettings(true)},icon={Icon(Icons.Default.Settings,null)},label={Text("Настройки")},modifier=Modifier.padding(horizontal=8.dp))
        Text("Nemotron 3 · локальная история", modifier = Modifier.padding(horizontal=18.dp,vertical=8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun BackendSettings(initialUrl: String, initialToken: String, initialPrompt:String, error: String?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember(initialToken) { mutableStateOf(initialToken) }
    var systemPrompt by remember(initialPrompt) { mutableStateOf(initialPrompt) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Key, null) },
        title = { Text("Настройки") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("NVIDIA и GitHub ключи хранятся на сервере. В приложение вводится только персональный токен устройства.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(url, { url = it }, label = { Text("Backend URL") }, singleLine = true)
                OutlinedTextField(token, { token = it }, label = { Text("WORKAI_DEVICE_TOKEN") }, singleLine = true)
                OutlinedTextField(systemPrompt,{systemPrompt=it},label={Text("Пользовательский системный промпт")},minLines=3,maxLines=7,supportingText={Text("Добавляется к системным инструкциям каждого нового запроса")})
                AnimatedVisibility(error != null) { Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(url, token,systemPrompt) }, enabled = url.isNotBlank() && token.length >= 20) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}
