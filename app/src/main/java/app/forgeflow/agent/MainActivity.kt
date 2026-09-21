package app.forgeflow.agent

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ForgeFlowApp() }
    }
}

enum class AgentState { IDLE, PLANNING, WORKING, BUILDING, FIXING, VERIFYING, COMPLETED, FAILED }
data class ActivityStep(val title: String, val state: StepState)
enum class StepState { WAITING, RUNNING, COMPLETED, FAILED }
data class Artifact(val name: String, val type: String, val size: String, val downloadUrl: String = "")
data class TaskUi(
    val prompt: String = "",
    val state: AgentState = AgentState.IDLE,
    val steps: List<ActivityStep> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val attachedNames: List<String> = emptyList(),
    val error: String? = null
)

class AgentViewModel(app: Application) : AndroidViewModel(app) {
    private val _ui = MutableStateFlow(TaskUi())
    val ui: StateFlow<TaskUi> = _ui.asStateFlow()

    fun updatePrompt(value: String) { _ui.value = _ui.value.copy(prompt = value) }
    fun attach(name: String) { _ui.value = _ui.value.copy(attachedNames = (_ui.value.attachedNames + name).distinct()) }
    fun run() {
        if (_ui.value.prompt.isBlank() || _ui.value.state !in listOf(AgentState.IDLE, AgentState.COMPLETED, AgentState.FAILED)) return
        viewModelScope.launch {
            val titles = listOf("Reading files", "Planning changes", "Editing workspace", "Building artifact", "Verifying result")
            _ui.value = _ui.value.copy(state = AgentState.PLANNING, artifacts = emptyList(), error = null,
                steps = titles.mapIndexed { i, t -> ActivityStep(t, if (i == 0) StepState.RUNNING else StepState.WAITING) })
            titles.indices.forEach { i ->
                delay(650)
                val s = _ui.value.steps.toMutableList()
                s[i] = s[i].copy(state = StepState.COMPLETED)
                if (i + 1 < s.size) s[i + 1] = s[i + 1].copy(state = StepState.RUNNING)
                val state = when (i) { 0 -> AgentState.WORKING; 2 -> AgentState.BUILDING; 3 -> AgentState.VERIFYING; else -> _ui.value.state }
                _ui.value = _ui.value.copy(steps = s, state = state)
            }
            _ui.value = _ui.value.copy(state = AgentState.COMPLETED,
                artifacts = listOf(Artifact("Connect backend to create files", "Configuration required", "—")))
        }
    }
}
