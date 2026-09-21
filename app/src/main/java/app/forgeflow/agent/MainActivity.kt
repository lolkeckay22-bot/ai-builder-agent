package app.forgeflow.agent

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ForgeFlowApp() }
    }
}

enum class WorkspaceMode { CHAT, WORK }
enum class MessageRole { USER, ASSISTANT }
enum class TodoState { WAITING, RUNNING, DONE, FAILED }

data class ChatMessage(val role: MessageRole, val text: String, val createdAt: Long = System.currentTimeMillis())
data class WorkTodo(val title: String, val state: TodoState = TodoState.WAITING)
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "Новый чат",
    val mode: WorkspaceMode = WorkspaceMode.CHAT,
    val messages: List<ChatMessage> = emptyList(),
    val todos: List<WorkTodo> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class AppUiState(
    val conversations: List<Conversation> = emptyList(),
    val activeId: String = "",
    val mode: WorkspaceMode = WorkspaceMode.CHAT,
    val input: String = "",
    val runningIds: Set<String> = emptySet(),
    val drawerOpen: Boolean = false,
    val settingsOpen: Boolean = false,
    val apiKey: String = "",
    val error: String? = null
) {
    val active: Conversation? get() = conversations.firstOrNull { it.id == activeId }
}

class AgentViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("forgeflow", 0)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    private val _ui = MutableStateFlow(load())
    val ui: StateFlow<AppUiState> = _ui.asStateFlow()

    fun setInput(value: String) { _ui.value = _ui.value.copy(input = value) }
    fun openDrawer(value: Boolean) { _ui.value = _ui.value.copy(drawerOpen = value) }
    fun openSettings(value: Boolean) { _ui.value = _ui.value.copy(settingsOpen = value) }
    fun clearError() { _ui.value = _ui.value.copy(error = null) }

    fun saveApiKey(value: String) {
        prefs.edit().putString("api_key", value.trim()).apply()
        _ui.value = _ui.value.copy(apiKey = value.trim(), settingsOpen = false, error = null)
    }

    fun switchMode(mode: WorkspaceMode) {
        val existing = _ui.value.conversations.filter { it.mode == mode }.maxByOrNull { it.updatedAt }
        if (existing != null) {
            _ui.value = _ui.value.copy(mode = mode, activeId = existing.id, drawerOpen = false)
        } else newChat(mode)
        persist()
    }

    fun newChat(mode: WorkspaceMode = _ui.value.mode) {
        val item = Conversation(mode = mode, title = if (mode == WorkspaceMode.CHAT) "Новый чат" else "Новая работа")
        _ui.value = _ui.value.copy(
            conversations = listOf(item) + _ui.value.conversations,
            activeId = item.id,
            mode = mode,
            input = "",
            drawerOpen = false,
            error = null
        )
        persist()
    }

    fun selectChat(id: String) {
        val item = _ui.value.conversations.firstOrNull { it.id == id } ?: return
        _ui.value = _ui.value.copy(activeId = id, mode = item.mode, drawerOpen = false, input = "", error = null)
        persist()
    }

    fun deleteChat(id: String) {
        val left = _ui.value.conversations.filterNot { it.id == id }
        val next = left.firstOrNull() ?: Conversation()
        _ui.value = _ui.value.copy(
            conversations = if (left.isEmpty()) listOf(next) else left,
            activeId = if (_ui.value.activeId == id) next.id else _ui.value.activeId,
            mode = if (_ui.value.activeId == id) next.mode else _ui.value.mode
        )
        persist()
    }

    fun send() {
        val prompt = _ui.value.input.trim()
        val key = _ui.value.apiKey
        val chat = _ui.value.active ?: return
        if (prompt.isBlank() || chat.id in _ui.value.runningIds) return
        if (key.isBlank()) {
            _ui.value = _ui.value.copy(settingsOpen = true, error = "Добавьте NVIDIA API key")
            return
        }

        val userMessage = ChatMessage(MessageRole.USER, prompt)
        val title = if (chat.messages.isEmpty()) prompt.take(42) else chat.title
        updateConversation(chat.id) { it.copy(title = title, messages = it.messages + userMessage, updatedAt = System.currentTimeMillis()) }
        _ui.value = _ui.value.copy(input = "", runningIds = _ui.value.runningIds + chat.id, error = null)
        persist()

        viewModelScope.launch {
            try {
                if (chat.mode == WorkspaceMode.WORK) runWork(chat.id, key) else runChat(chat.id, key)
            } catch (t: Throwable) {
                updateConversation(chat.id) { current ->
                    current.copy(
                        messages = current.messages + ChatMessage(MessageRole.ASSISTANT, "Ошибка: ${t.message ?: "неизвестная ошибка"}"),
                        todos = current.todos.map { if (it.state == TodoState.RUNNING) it.copy(state = TodoState.FAILED) else it }
                    )
                }
                _ui.value = _ui.value.copy(error = t.message)
            } finally {
                _ui.value = _ui.value.copy(runningIds = _ui.value.runningIds - chat.id)
                persist()
            }
        }
    }

    private suspend fun runChat(id: String, key: String) {
        val current = _ui.value.conversations.first { it.id == id }
        val answer = complete(key, current.messages, "Ты полезный русскоязычный ассистент. Отвечай точно и кратко.")
        updateConversation(id) { it.copy(messages = it.messages + ChatMessage(MessageRole.ASSISTANT, answer), updatedAt = System.currentTimeMillis()) }
    }

    private suspend fun runWork(id: String, key: String) {
        val current = _ui.value.conversations.first { it.id == id }
        val lastPrompt = current.messages.last { it.role == MessageRole.USER }.text
        val planner = listOf(ChatMessage(MessageRole.USER, lastPrompt))
        val planRaw = complete(
            key,
            planner,
            "Составь короткий выполнимый план. Верни только JSON: {\"tasks\":[\"задача 1\",\"задача 2\"]}. От 2 до 6 пунктов. Не обещай создать файл без доступных инструментов."
        )
        val tasks = parseTasks(planRaw).ifEmpty { listOf("Проанализировать запрос", "Подготовить результат", "Проверить ответ") }
        updateConversation(id) { it.copy(todos = tasks.mapIndexed { index, s -> WorkTodo(s, if (index == 0) TodoState.RUNNING else TodoState.WAITING) }) }
        persist()

        val workSystem = """
            Ты агент в режиме Работа. Выполни запрос по плану настолько полно, насколько позволяют текстовые инструменты.
            Не утверждай, что APK или другой файл создан, если у тебя нет реального URL/артефакта.
            Если для компиляции нужен сервер, прямо укажи это. Отвечай на русском.
            План: ${tasks.joinToString("; ")}
        """.trimIndent()
        val answer = complete(key, _ui.value.conversations.first { it.id == id }.messages, workSystem)
        updateConversation(id) { conversation ->
            conversation.copy(
                messages = conversation.messages + ChatMessage(MessageRole.ASSISTANT, answer),
                todos = conversation.todos.map { it.copy(state = TodoState.DONE) },
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun parseTasks(raw: String): List<String> = try {
        val clean = raw.substringAfter('{', "").let { if (it.isBlank()) raw else "{$it" }.substringBeforeLast('}') + "}"
        val array = JSONObject(clean).optJSONArray("tasks") ?: JSONArray()
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }.take(6)
    } catch (_: Throwable) { emptyList() }

    private suspend fun complete(key: String, messages: List<ChatMessage>, system: String): String = withContext(Dispatchers.IO) {
        val requestMessages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        messages.takeLast(30).forEach {
            requestMessages.put(JSONObject().put("role", if (it.role == MessageRole.USER) "user" else "assistant").put("content", it.text))
        }
        val body = JSONObject()
            .put("model", "nvidia/nemotron-3-super-120b-a12b")
            .put("messages", requestMessages)
            .put("temperature", 0.6)
            .put("max_tokens", 2048)
            .put("stream", false)
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/chat/completions")
            .header("Authorization", "Bearer $key")
            .header("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("NVIDIA API ${response.code}: ${JSONObject(text).optString("detail", text.take(240))}")
            JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim()
        }
    }

    private fun updateConversation(id: String, transform: (Conversation) -> Conversation) {
        _ui.value = _ui.value.copy(conversations = _ui.value.conversations.map { if (it.id == id) transform(it) else it })
        persist()
    }

    private fun persist() {
        val root = JSONArray()
        _ui.value.conversations.forEach { c ->
            val messages = JSONArray()
            c.messages.forEach { messages.put(JSONObject().put("role", it.role.name).put("text", it.text).put("at", it.createdAt)) }
            val todos = JSONArray()
            c.todos.forEach { todos.put(JSONObject().put("title", it.title).put("state", it.state.name)) }
            root.put(JSONObject().put("id", c.id).put("title", c.title).put("mode", c.mode.name).put("updated", c.updatedAt).put("messages", messages).put("todos", todos))
        }
        prefs.edit().putString("chats", root.toString()).putString("active", _ui.value.activeId).apply()
    }

    private fun load(): AppUiState {
        val saved = mutableListOf<Conversation>()
        try {
            val array = JSONArray(prefs.getString("chats", "[]"))
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val messages = mutableListOf<ChatMessage>()
                val ma = o.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until ma.length()) ma.getJSONObject(j).let { messages += ChatMessage(MessageRole.valueOf(it.getString("role")), it.getString("text"), it.optLong("at")) }
                val todos = mutableListOf<WorkTodo>()
                val ta = o.optJSONArray("todos") ?: JSONArray()
                for (j in 0 until ta.length()) ta.getJSONObject(j).let { todos += WorkTodo(it.getString("title"), TodoState.valueOf(it.getString("state"))) }
                saved += Conversation(o.getString("id"), o.getString("title"), WorkspaceMode.valueOf(o.getString("mode")), messages, todos, o.optLong("updated"))
            }
        } catch (_: Throwable) { saved.clear() }
        val chats = saved.ifEmpty { listOf(Conversation()) }
        val active = prefs.getString("active", null)?.takeIf { id -> chats.any { it.id == id } } ?: chats.first().id
        val selected = chats.first { it.id == active }
        return AppUiState(conversations = chats, activeId = active, mode = selected.mode, apiKey = prefs.getString("api_key", "").orEmpty())
    }
}
