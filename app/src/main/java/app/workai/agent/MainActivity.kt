package app.workai.agent

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.ByteArrayOutputStream
import android.util.Base64
import java.security.MessageDigest
import androidx.core.content.ContextCompat
import java.util.zip.ZipInputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { WorkAIApp() } }
}

enum class WorkspaceMode { CHAT, WORK }
enum class MessageRole { USER, ASSISTANT }
enum class TodoState { WAITING, RUNNING, DONE, FAILED }
data class MessageAttachment(val name:String, val mime:String)
data class WebSource(val url:String,val title:String,val domain:String,val snippet:String,val favicon:String)
data class AgentActivity(val label:String,val icon:String="search")
enum class ExecutionEventType { THINKING, STATUS, TOOL, TEXT, ERROR }
enum class ExecutionState { RUNNING, COMPLETED, CANCELLED, FAILED }
data class ExecutionEvent(val type:ExecutionEventType,val text:String,val icon:String="terminal",val at:Long=System.currentTimeMillis())
data class ExecutionSession(
    val id:String=UUID.randomUUID().toString(),
    val startedAt:Long=System.currentTimeMillis(),
    val completedAt:Long?=null,
    val state:ExecutionState=ExecutionState.RUNNING,
    val events:List<ExecutionEvent> = emptyList()
)
data class ChatMessage(val role: MessageRole, val text: String, val createdAt: Long = System.currentTimeMillis(), val attachments:List<MessageAttachment> = emptyList(), val context:String? = null, val thinking:String? = null, val activities:List<AgentActivity> = emptyList(), val execution:ExecutionSession?=null, val artifact:WorkArtifact?=null,val sources:List<WebSource> = emptyList(),val workHandoff:String?=null)
data class WorkTodo(val title: String, val state: TodoState = TodoState.WAITING)
data class WorkArtifact(val jobId: String, val name: String, val size: Long)
data class PendingAttachment(val name: String, val mime: String, val bytes: ByteArray, val text: String? = null)
enum class DownloadPhase { DOWNLOADING, COMPLETE }
data class DownloadNotice(val phase:DownloadPhase,val name:String,val uri:String?=null,val mime:String="application/octet-stream")
data class Conversation(val id:String=UUID.randomUUID().toString(), val title:String="Новый чат", val mode:WorkspaceMode=WorkspaceMode.CHAT, val messages:List<ChatMessage> = emptyList(), val todos:List<WorkTodo> = emptyList(), val artifact:WorkArtifact?=null, val jobId:String?=null, val updatedAt:Long=System.currentTimeMillis())
data class AppUiState(val conversations:List<Conversation> = emptyList(), val activeId:String="", val mode:WorkspaceMode=WorkspaceMode.CHAT, val input:String="", val runningIds:Set<String> = emptySet(), val drawerOpen:Boolean=false, val settingsOpen:Boolean=false, val backendUrl:String=DEFAULT_BACKEND, val deviceToken:String="", val systemPrompt:String="", val selectedModel:String=MODEL_SUPER, val reasoningEffort:String="low", val attachments:List<PendingAttachment> = emptyList(), val error:String?=null,val downloadNotice:DownloadNotice?=null) { val active get()=conversations.firstOrNull { it.id==activeId } }

const val DEFAULT_BACKEND="https://workai-backend.lolkeckay222.workers.dev"
const val MODEL_SUPER="nvidia/nemotron-3-super-120b-a12b"
const val MODEL_ULTRA="nvidia/nemotron-3-ultra-550b-a55b"
const val MODEL_AGNES_25="agnes-2.5-flash"
const val MODEL_AGNES_30="agnes-3.0-flash"

class AgentViewModel(app:Application):AndroidViewModel(app) {
    private val prefs=app.getSharedPreferences("workai",0)
    private val client=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).build()
    private val jobs=mutableMapOf<String,Job>()
    private val calls=mutableMapOf<String,Call>()
    private val handoffFiles=mutableMapOf<String,List<PendingAttachment>>()
    private val _ui=MutableStateFlow(load()); val ui:StateFlow<AppUiState> = _ui.asStateFlow()
    fun setInput(v:String){_ui.value=_ui.value.copy(input=v)}
    fun selectModel(v:String){
        val allowed=if(v==MODEL_ULTRA)setOf("none","medium","high") else if(v.startsWith("agnes-"))setOf("none","low","medium","high") else setOf("none","low","high")
        val effort=_ui.value.reasoningEffort.takeIf{it in allowed}?:if(v==MODEL_ULTRA)"medium" else "low"
        prefs.edit().putString("model",v).putString("reasoning_effort",effort).apply()
        _ui.value=_ui.value.copy(selectedModel=v,reasoningEffort=effort)
    }
    fun selectReasoning(v:String){
        val allowed=if(_ui.value.selectedModel==MODEL_ULTRA)setOf("none","medium","high") else if(_ui.value.selectedModel.startsWith("agnes-"))setOf("none","low","medium","high") else setOf("none","low","high")
        if(v !in allowed)return
        prefs.edit().putString("reasoning_effort",v).apply();_ui.value=_ui.value.copy(reasoningEffort=v)
    }
    fun addAttachments(uris:List<Uri>){
        if(uris.isEmpty())return
        viewModelScope.launch(Dispatchers.IO){
            val resolver=getApplication<Application>().contentResolver
            val added=mutableListOf<PendingAttachment>();val failures=mutableListOf<String>()
            uris.take(8).forEach{uri->
                runCatching{
                    val name=resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0) else null}?:uri.lastPathSegment?:"Файл"
                    val mime=resolver.getType(uri)?:"application/octet-stream"
                    val bytes=resolver.openInputStream(uri)?.use{it.readBytes()}?:error("Не удалось прочитать $name")
                    if(bytes.size>100*1024*1024)error("$name больше 100 МБ")
                    val text=when{name.endsWith(".mtz",true)||name.endsWith(".zip",true)->inspectArchive(bytes,name);listOf(".docx",".pptx",".xlsx",".odt").any{name.endsWith(it,true)}->inspectOffice(bytes,name);mime.startsWith("text/")||listOf(".txt",".csv",".kt",".java",".json",".xml",".md",".gradle",".properties",".html",".css",".js",".py",".yaml",".yml",".log").any{name.endsWith(it,true)}->bytes.toString(Charsets.UTF_8).take(300_000);else->"Файл $name (${bytes.size} байт) загружен полностью и доступен рабочему агенту."}
                    added+=PendingAttachment(name,mime,bytes,text)
                }.onFailure{failures+=it.message?:"Ошибка чтения файла"}
            }
            withContext(Dispatchers.Main){_ui.value=_ui.value.copy(attachments=(_ui.value.attachments+added).distinctBy{it.name}.take(8),error=failures.firstOrNull())}
        }
    }
    private fun inspectArchive(data:ByteArray,name:String):String{val out=StringBuilder("Содержимое архива $name:\n");ZipInputStream(data.inputStream()).use{zip->var entry=zip.nextEntry;var total=0;val buffer=ByteArray(8192);while(entry!=null&&total<180_000){out.append("- ").append(entry.name).append(if(entry.isDirectory)"/" else "").append('\n');val lower=entry.name.lowercase();val readable=!entry.isDirectory&&(lower.endsWith(".xml")||lower.endsWith(".json")||lower.endsWith(".txt")||lower.endsWith(".html")||lower.endsWith(".css")||lower.endsWith(".js")||lower.endsWith(".properties")||lower.endsWith("manifest"));if(readable){val bytes=ByteArrayOutputStream();while(bytes.size()<32_000){val n=zip.read(buffer,0,minOf(buffer.size,32_000-bytes.size()));if(n<=0)break;bytes.write(buffer,0,n)};val value=bytes.toString(Charsets.UTF_8.name()).replace("\u0000","");out.append("```\n").append(value.take(32_000)).append("\n```\n");total+=value.length};zip.closeEntry();entry=zip.nextEntry}};return out.take(180_000).toString()}
    private fun inspectOffice(data:ByteArray,name:String):String{val out=StringBuilder("Текст документа $name:\n");ZipInputStream(data.inputStream()).use{zip->var e=zip.nextEntry;while(e!=null&&out.length<300000){val n=e.name.lowercase();if(!e.isDirectory&&(n.endsWith("document.xml")||n.startsWith("word/")&&n.endsWith(".xml")||n.startsWith("ppt/slides/")&&n.endsWith(".xml")||n.startsWith("xl/sharedstrings"))){val raw=zip.readBytes().toString(Charsets.UTF_8);out.append(raw.replace(Regex("<[^>]+>")," ").replace("&amp;","&").replace("&lt;","<").replace("&gt;",">").replace(Regex("\\s+")," ")).append('\n')};zip.closeEntry();e=zip.nextEntry}};return out.take(300000).toString()}
    fun removeAttachment(name:String){_ui.value=_ui.value.copy(attachments=_ui.value.attachments.filterNot{it.name==name})}
    fun openDrawer(v:Boolean){_ui.value=_ui.value.copy(drawerOpen=v)}
    fun openSettings(v:Boolean){_ui.value=_ui.value.copy(settingsOpen=v)}
    fun saveBackend(url:String,token:String,systemPrompt:String){val clean=url.trim().trimEnd('/').ifBlank{DEFAULT_BACKEND};prefs.edit().putString("backend",clean).putString("device_token",token.trim()).putString("system_prompt",systemPrompt.trim()).apply();_ui.value=_ui.value.copy(backendUrl=clean,deviceToken=token.trim(),systemPrompt=systemPrompt.trim(),settingsOpen=false,error=null)}
    fun switchMode(mode:WorkspaceMode){val c=_ui.value.conversations.filter{it.mode==mode}.maxByOrNull{it.updatedAt};if(c==null)newChat(mode)else _ui.value=_ui.value.copy(mode=mode,activeId=c.id,drawerOpen=false);persist()}
    fun newChat(mode:WorkspaceMode=_ui.value.mode){val c=Conversation(mode=mode,title=if(mode==WorkspaceMode.CHAT)"Новый чат" else "Новая работа");_ui.value=_ui.value.copy(conversations=listOf(c)+_ui.value.conversations,activeId=c.id,mode=mode,input="",drawerOpen=false,error=null);persist()}
    fun selectChat(id:String){val c=_ui.value.conversations.firstOrNull{it.id==id}?:return;_ui.value=_ui.value.copy(activeId=id,mode=c.mode,drawerOpen=false,input="",error=null)}
    fun deleteChat(id:String){val left=_ui.value.conversations.filterNot{it.id==id};val next=left.firstOrNull()?:Conversation();_ui.value=_ui.value.copy(conversations=left.ifEmpty{listOf(next)},activeId=if(_ui.value.activeId==id)next.id else _ui.value.activeId,mode=if(_ui.value.activeId==id)next.mode else _ui.value.mode);persist()}

    fun send(){
        val rawPrompt=_ui.value.input.trim();val chat=_ui.value.active?:return
        val files=_ui.value.attachments
        val prompt=buildString{append(rawPrompt);files.forEach{f->append("\n\n[Прикреплён файл: ${f.name}, ${f.mime}]");f.text?.let{append("\n");append(it)}}}
        if(prompt.isBlank()||chat.id in _ui.value.runningIds)return
        if(_ui.value.deviceToken.length<20){_ui.value=_ui.value.copy(settingsOpen=true,error="Введите WORKAI_DEVICE_TOKEN из GitHub Secrets");return}
        val title=if(chat.messages.isEmpty())prompt.take(42)else chat.title
        val cards=files.map{MessageAttachment(it.name,it.mime)}
        update(chat.id){it.copy(title=title,messages=it.messages+ChatMessage(MessageRole.USER,rawPrompt,attachments=cards,context=prompt),updatedAt=System.currentTimeMillis())}
        _ui.value=_ui.value.copy(input="",attachments=emptyList(),runningIds=_ui.value.runningIds+chat.id,error=null)
        ContextCompat.startForegroundService(getApplication(),Intent(getApplication(),AgentForegroundService::class.java))
        val creation=needsArtifact(rawPrompt,files)
        val handoff=rawPrompt.takeIf{chat.mode==WorkspaceMode.CHAT&&creation}
        if(handoff!=null)handoffFiles[handoff]=files
        update(chat.id){it.copy(messages=it.messages+ChatMessage(MessageRole.ASSISTANT,"",execution=ExecutionSession(),workHandoff=handoff))}
        jobs[chat.id]=viewModelScope.launch{try{if(chat.mode==WorkspaceMode.WORK&&creation)runWork(chat.id,prompt,files)else runChat(chat.id,creation)}catch(e:CancellationException){finishExecution(chat.id,ExecutionState.CANCELLED,"Остановлено пользователем.")}catch(e:Throwable){appendEvent(chat.id,ExecutionEventType.ERROR,e.message?:"Не удалось выполнить запрос","error");finishExecution(chat.id,ExecutionState.FAILED);_ui.value=_ui.value.copy(error=e.message)}finally{calls.remove(chat.id);jobs.remove(chat.id);_ui.value=_ui.value.copy(runningIds=_ui.value.runningIds-chat.id);if(_ui.value.runningIds.isEmpty())getApplication<Application>().stopService(Intent(getApplication(),AgentForegroundService::class.java));persist()}}
    }
    private fun needsArtifact(text:String,files:List<PendingAttachment>):Boolean{
        val mutation=Regex("(?i)(создай|сделай|собери|сгенерируй|сформируй|упакуй|заархивируй|измени|замени|перекрась|отредактируй|добавь|удали|перезалей|верни|отправь)").containsMatchIn(text)
        val result=Regex("(?i)(файл|архив|zip|rar|7z|mtz|apk|docx|txt|pdf|готов(?:ый|ую)|скачать)").containsMatchIn(text)
        return mutation&&(result||files.isNotEmpty())
    }
    fun stop(){_ui.value.activeId.let{calls.remove(it)?.cancel();jobs[it]?.cancel()}}

    private suspend fun runChat(id:String,creationRequest:Boolean=false){val chat=_ui.value.conversations.first{it.id==id};val a=JSONArray();chat.messages.dropLast(1).takeLast(30).forEach{a.put(JSONObject().put("role",if(it.role==MessageRole.USER)"user" else "assistant").put("content",it.context?:it.text))};streamApi(id,JSONObject().put("messages",a).put("model",_ui.value.selectedModel).put("reasoning_effort",_ui.value.reasoningEffort).put("system_prompt",_ui.value.systemPrompt).put("mode",chat.mode.name.lowercase()).put("creation_request",creationRequest))}
    private fun mutateExecution(id:String, transform:(ChatMessage)->ChatMessage){update(id){c->val list=c.messages.toMutableList();val index=list.indexOfLast{it.role==MessageRole.ASSISTANT&&it.execution?.state==ExecutionState.RUNNING};if(index>=0)list[index]=transform(list[index]);c.copy(messages=list,updatedAt=System.currentTimeMillis())}}
    private fun appendEvent(id:String,type:ExecutionEventType,text:String,icon:String="terminal"){if(text.isBlank())return;mutateExecution(id){m->
        val session=m.execution?:return@mutateExecution m;val events=session.events.toMutableList();val last=events.lastOrNull()
        if(type==ExecutionEventType.THINKING&&last?.type==type)events[events.lastIndex]=last.copy(text=last.text+text)
        else if(last?.type!=type||last.text!=text)events+=ExecutionEvent(type,text,icon)
        m.copy(execution=session.copy(events=events))
    }}
    private fun startThinking(id:String){mutateExecution(id){m->val s=m.execution?:return@mutateExecution m;m.copy(execution=s.copy(events=s.events+ExecutionEvent(ExecutionEventType.THINKING,"","thinking")))}}
    private fun appendThinking(id:String,delta:String){if(delta.isEmpty())return;mutateExecution(id){m->val s=m.execution?:return@mutateExecution m;val events=s.events.toMutableList();if(events.lastOrNull()?.type==ExecutionEventType.THINKING)events[events.lastIndex]=events.last().copy(text=events.last().text+delta) else events+=ExecutionEvent(ExecutionEventType.THINKING,delta,"thinking");m.copy(execution=s.copy(events=events))}}
    private fun appendText(id:String,delta:String){if(delta.isEmpty())return;mutateExecution(id){it.copy(text=it.text+delta)}}
    private fun finishExecution(id:String,state:ExecutionState=ExecutionState.COMPLETED,message:String?=null){mutateExecution(id){m->m.copy(text=if(m.text.isBlank()&&!message.isNullOrBlank())message else m.text,execution=m.execution?.copy(state=state,completedAt=System.currentTimeMillis()))}}
    private suspend fun streamApi(id:String,body:JSONObject)=withContext(Dispatchers.IO){val request=builder("/v1/chat/stream").post(body.toString().toRequestBody("application/json".toMediaType())).build();val call=client.newCall(request);calls[id]=call;call.execute().use{response->if(!response.isSuccessful)error("Backend ${response.code}: ${response.body?.string()?.take(300)}");val source=response.body?.source()?:error("Пустой ответ");var completed=false;while(!source.exhausted()){val line=source.readUtf8Line()?:continue;if(!line.startsWith("data: "))continue;val data=line.removePrefix("data: ").trim();if(data=="[DONE]")continue;val event=runCatching{JSONObject(data)}.getOrNull()?:continue;val type=event.optString("type");withContext(Dispatchers.Main){when(type){"thinking_start"->startThinking(id);"thinking_delta"->appendThinking(id,event.optString("delta"));"intermediate"->appendEvent(id,ExecutionEventType.TEXT,event.optString("text"),"message");"status"->appendEvent(id,ExecutionEventType.STATUS,event.optString("text"),event.optString("icon","terminal"));"tool_call"->appendEvent(id,ExecutionEventType.TOOL,event.optString("label"),event.optString("icon","terminal"));"tool_result"->{val status=event.optString("status");appendEvent(id,if(status=="tool_success")ExecutionEventType.STATUS else ExecutionEventType.ERROR,event.optString("text"),if(status=="tool_success")event.optString("icon","terminal") else "error")};"context_snapshot"->mutateExecution(id){it.copy(context=event.optString("text"))};"text_delta"->appendText(id,event.optString("delta"));"sources"->{val a=event.optJSONArray("items")?:JSONArray();val items=(0 until a.length()).mapNotNull{i->a.optJSONObject(i)?.let{s->WebSource(s.optString("url"),s.optString("title"),s.optString("domain"),s.optString("snippet"),s.optString("favicon"))}}.distinctBy{it.url};mutateExecution(id){it.copy(sources=items)}};"artifact"->{val a=event.optJSONObject("artifact")?:event;mutateExecution(id){it.copy(artifact=WorkArtifact(a.optString("job_id"),a.optString("name"),a.optLong("size")))}};"task_completed"->{finishExecution(id);completed=true};"error"->{appendEvent(id,ExecutionEventType.ERROR,event.optString("message"),"error");finishExecution(id,ExecutionState.FAILED);completed=true};"done"->{if(!completed){finishExecution(id);completed=true}};else->{val delta=event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty();if(delta.isNotEmpty())appendText(id,delta)}}}};if(!completed)error("Поток завершился без события task_completed")}}

    private fun sha256(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    private suspend fun uploadChunk(id:String,bytes:ByteArray,index:Int):JSONObject{
        val digest=sha256(bytes)
        var failure:Throwable?=null
        repeat(4){attempt->
            try{
                val uploaded=api("/v1/uploads/blob","POST",JSONObject().put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("sha256",digest).put("size",bytes.size).put("index",index),id)
                if(uploaded.optString("sha256")!=digest||uploaded.optInt("size")!=bytes.size)error("Сервер не подтвердил целостность части ${index+1}")
                return uploaded
            }catch(e:Throwable){failure=e;if(attempt<3)delay(600L*(1 shl attempt))}
        }
        throw failure?:IllegalStateException("Не удалось загрузить часть ${index+1}")
    }
    private suspend fun runWork(id:String,prompt:String,files:List<PendingAttachment>){
        val kind=when{
            prompt.contains("mtz",true)||prompt.contains("тема hyperos",true)||prompt.contains("тема miui",true)->"mtz"
            Regex("(?i)\\bapk\\b|android[- ]?приложен|собер[иь].{0,30}приложен").containsMatchIn(prompt)->"apk"
            else->"zip"
        }
        val payloadFiles=JSONArray();files.forEach{file->appendEvent(id,ExecutionEventType.TOOL,"Загружаю ${file.name}","file");val chunks=JSONArray();var offset=0;var index=0;while(offset<file.bytes.size){val end=minOf(offset+5*1024*1024,file.bytes.size);val part=file.bytes.copyOfRange(offset,end);val uploaded=uploadChunk(id,part,index);chunks.put(JSONObject().put("sha",uploaded.getString("sha")).put("sha256",uploaded.getString("sha256")).put("size",part.size).put("index",index));offset=end;index++};payloadFiles.put(JSONObject().put("name",file.name).put("mime",file.mime).put("chunks",chunks).put("size",file.bytes.size).put("sha256",sha256(file.bytes)));appendEvent(id,ExecutionEventType.STATUS,"Файл ${file.name} загружен и проверен","file")}
        appendEvent(id,ExecutionEventType.TOOL,if(files.isEmpty())"Создаю файлы" else "Анализирую и изменяю файлы","build")
        val started=api("/v1/jobs","POST",JSONObject().put("prompt",prompt).put("kind",kind).put("attachments",payloadFiles).put("model",_ui.value.selectedModel).put("reasoning_effort",_ui.value.reasoningEffort).put("system_prompt",_ui.value.systemPrompt),id)
        started.optString("intro").takeIf{it.isNotBlank()}?.let{appendEvent(id,ExecutionEventType.TEXT,it,"message")}
        val skills=started.optJSONArray("skills")?:JSONArray();for(i in 0 until skills.length())appendEvent(id,ExecutionEventType.STATUS,"Использую ${skills.optString(i)}","build")
        val job=started.getString("id");val t=started.optJSONArray("tasks")?:JSONArray();val todos=(0 until t.length()).map{WorkTodo(t.getString(it),if(it==0)TodoState.RUNNING else TodoState.WAITING)};update(id){it.copy(jobId=job,todos=todos)}
        var lastStep=""
        repeat(300){
            delay(5000)
            val s=api("/v1/jobs/$job","GET")
            val status=s.optString("status")
            val conclusion=s.optString("conclusion")
            val steps=s.optJSONArray("steps")?:JSONArray()
            update(id){c->c.copy(todos=updatedTodos(c.todos,steps,status,conclusion))}
            val current=(0 until steps.length()).map{steps.getJSONObject(it)}.lastOrNull{it.optString("status")=="in_progress"}?.optString("title").orEmpty()
            if(current.isNotBlank()&&current!=lastStep){appendEvent(id,ExecutionEventType.STATUS,current,"build");lastStep=current}
            if(status=="completed"){
                if(conclusion!="success")error("Создание файла завершилось с ошибкой")
                val a=s.optJSONObject("artifact")?:error("Файл не найден")
                val artifact=WorkArtifact(job,a.getString("name"),a.optLong("size"));mutateExecution(id){it.copy(text="Готово. Файл создан, проверен и доступен для скачивания.",artifact=artifact)};appendEvent(id,ExecutionEventType.STATUS,"Готовый файл опубликован","file");finishExecution(id)
                return
            }
        }
        error("Превышено время ожидания сборки")
    }

    private fun updatedTodos(items:List<WorkTodo>,steps:JSONArray,status:String,conclusion:String):List<WorkTodo>{
        if(status=="completed"&&conclusion=="success")return items.map{it.copy(state=TodoState.DONE)}
        val actual=(0 until steps.length()).map{steps.optJSONObject(it)}.filter{it!=null&&it.optString("title") !in setOf("Set up job","Complete job")}
        val completed=actual.count{it.optString("status")=="completed"&&it.optString("conclusion")=="success"}
        val total=actual.size.coerceAtLeast(1)
        val done=((completed.toDouble()/total)*items.size).toInt().coerceIn(0,items.size)
        val active=if(done>=items.size)items.lastIndex else done
        return items.mapIndexed{index,item->when{index<done->item.copy(state=TodoState.DONE);status=="completed"&&conclusion!="success"&&index==active->item.copy(state=TodoState.FAILED);index==active->item.copy(state=TodoState.RUNNING);else->item.copy(state=TodoState.WAITING)}}
    }

    fun downloadArtifact(a:WorkArtifact,share:Boolean){viewModelScope.launch{val mime=if(a.name.endsWith(".apk"))"application/vnd.android.package-archive" else "application/octet-stream";_ui.value=_ui.value.copy(downloadNotice=DownloadNotice(DownloadPhase.DOWNLOADING,a.name));try{val uri=withContext(Dispatchers.IO){client.newCall(builder("/v1/jobs/${a.jobId}/download").get().build()).execute().use{r->if(!r.isSuccessful)error("Download ${r.code}");val values=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,a.name);put(MediaStore.Downloads.MIME_TYPE,mime);put(MediaStore.Downloads.IS_PENDING,1)};val resolver=getApplication<Application>().contentResolver;val u=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Не удалось создать файл");resolver.openOutputStream(u)!!.use{o->r.body!!.byteStream().copyTo(o)};values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);resolver.update(u,values,null,null);u}};_ui.value=_ui.value.copy(downloadNotice=DownloadNotice(DownloadPhase.COMPLETE,a.name,uri.toString(),mime));if(share){val i=Intent(Intent.ACTION_SEND).apply{type=mime;putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)};getApplication<Application>().startActivity(Intent.createChooser(i,"Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}catch(e:Throwable){_ui.value=_ui.value.copy(downloadNotice=null,error=e.message)}}}
    fun dismissDownload(){_ui.value=_ui.value.copy(downloadNotice=null)}
    fun openDownload(){val n=_ui.value.downloadNotice?:return;val uri=n.uri?.let(Uri::parse)?:return;runCatching{getApplication<Application>().startActivity(Intent(Intent.ACTION_VIEW).apply{setDataAndType(uri,n.mime);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)})}.onFailure{_ui.value=_ui.value.copy(error="Нет приложения для открытия файла")}}
    fun acceptWorkHandoff(prompt:String){val c=Conversation(mode=WorkspaceMode.WORK,title=prompt.take(42));_ui.value=_ui.value.copy(conversations=listOf(c)+_ui.value.conversations,activeId=c.id,mode=WorkspaceMode.WORK,input=prompt,attachments=handoffFiles.remove(prompt).orEmpty(),drawerOpen=false,error=null);persist()}
    fun dismissWorkHandoff(messageAt:Long){update(_ui.value.activeId){c->c.copy(messages=c.messages.map{if(it.createdAt==messageAt)it.copy(workHandoff=null) else it})}}
    private fun builder(path:String)=Request.Builder().url(_ui.value.backendUrl+path).header("X-WorkAI-Token",_ui.value.deviceToken).header("Accept","application/json")
    private suspend fun api(path:String,method:String,body:JSONObject?=null,callId:String?=null):JSONObject=withContext(Dispatchers.IO){val b=builder(path);if(method=="POST")b.post((body?:JSONObject()).toString().toRequestBody("application/json".toMediaType()))else b.get();val call=client.newCall(b.build());if(callId!=null)calls[callId]=call;call.execute().use{r->val text=r.body?.string().orEmpty();if(!r.isSuccessful)error("Backend ${r.code}: ${runCatching{JSONObject(text).optString("detail",text)}.getOrDefault(text).take(400)}");JSONObject(text)}}
    private fun update(id:String,save:Boolean=true,f:(Conversation)->Conversation){_ui.value=_ui.value.copy(conversations=_ui.value.conversations.map{if(it.id==id)f(it)else it});if(save)persist()}
    private fun persist(){val root=JSONArray();_ui.value.conversations.forEach{c->val m=JSONArray();c.messages.forEach{msg->val files=JSONArray();msg.attachments.forEach{files.put(JSONObject().put("name",it.name).put("mime",it.mime))};val sources=JSONArray();msg.sources.forEach{sources.put(JSONObject().put("url",it.url).put("title",it.title).put("domain",it.domain).put("snippet",it.snippet).put("favicon",it.favicon))};val o=JSONObject().put("role",msg.role.name).put("text",msg.text).put("context",msg.context).put("at",msg.createdAt).put("files",files).put("sources",sources).put("work_handoff",msg.workHandoff);msg.execution?.let{s->val events=JSONArray();s.events.forEach{events.put(JSONObject().put("type",it.type.name).put("text",it.text).put("icon",it.icon).put("at",it.at))};o.put("execution",JSONObject().put("id",s.id).put("started",s.startedAt).put("completed",s.completedAt).put("state",s.state.name).put("events",events))};msg.artifact?.let{o.put("artifact",JSONObject().put("job",it.jobId).put("name",it.name).put("size",it.size))};m.put(o)};root.put(JSONObject().put("id",c.id).put("title",c.title).put("mode",c.mode.name).put("updated",c.updatedAt).put("messages",m))};prefs.edit().putString("chats",root.toString()).putString("active",_ui.value.activeId).apply()}
    private fun load():AppUiState{val saved=mutableListOf<Conversation>();runCatching{val a=JSONArray(prefs.getString("chats","[]"));for(i in 0 until a.length()){val o=a.getJSONObject(i);val m=mutableListOf<ChatMessage>();val ma=o.optJSONArray("messages")?:JSONArray();for(j in 0 until ma.length()){val x=ma.getJSONObject(j);val files=x.optJSONArray("files")?:JSONArray();val attachments=(0 until files.length()).map{files.getJSONObject(it).let{f->MessageAttachment(f.optString("name"),f.optString("mime"))}};val sourceJson=x.optJSONArray("sources")?:JSONArray();val sources=(0 until sourceJson.length()).map{sourceJson.getJSONObject(it).let{s->WebSource(s.optString("url"),s.optString("title"),s.optString("domain"),s.optString("snippet"),s.optString("favicon"))}};val execution=x.optJSONObject("execution")?.let{s->val ea=s.optJSONArray("events")?:JSONArray();ExecutionSession(s.optString("id",UUID.randomUUID().toString()),s.optLong("started",x.optLong("at")),s.optLong("completed").takeIf{it>0},runCatching{ExecutionState.valueOf(s.optString("state"))}.getOrDefault(ExecutionState.COMPLETED),(0 until ea.length()).map{ea.getJSONObject(it).let{e->ExecutionEvent(runCatching{ExecutionEventType.valueOf(e.optString("type"))}.getOrDefault(ExecutionEventType.STATUS),e.optString("text"),e.optString("icon","terminal"),e.optLong("at"))}})};val artifact=x.optJSONObject("artifact")?.let{WorkArtifact(it.optString("job"),it.optString("name"),it.optLong("size"))};m+=ChatMessage(MessageRole.valueOf(x.getString("role")),x.optString("text"),x.optLong("at"),attachments,x.optString("context").takeIf{it.isNotBlank()&&it!="null"},execution=execution,artifact=artifact,sources=sources,workHandoff=x.optString("work_handoff").takeIf{it.isNotBlank()&&it!="null"})};saved+=Conversation(id=o.getString("id"),title=o.getString("title"),mode=WorkspaceMode.valueOf(o.getString("mode")),messages=m,updatedAt=o.optLong("updated"))}};val chats=saved.ifEmpty{listOf(Conversation())};val active=prefs.getString("active",null)?.takeIf{id->chats.any{it.id==id}}?:chats.first().id;val selected=chats.first{it.id==active};val model=prefs.getString("model",MODEL_SUPER)?:MODEL_SUPER;val allowed=if(model==MODEL_ULTRA)setOf("none","medium","high") else if(model.startsWith("agnes-"))setOf("none","low","medium","high") else setOf("none","low","high");val effort=(prefs.getString("reasoning_effort",null)).takeIf{it in allowed}?:if(model==MODEL_ULTRA)"medium" else "low";return AppUiState(conversations=chats,activeId=active,mode=selected.mode,backendUrl=prefs.getString("backend",DEFAULT_BACKEND)?:DEFAULT_BACKEND,deviceToken=prefs.getString("device_token","").orEmpty(),systemPrompt=prefs.getString("system_prompt","").orEmpty(),selectedModel=model,reasoningEffort=effort)}
}
