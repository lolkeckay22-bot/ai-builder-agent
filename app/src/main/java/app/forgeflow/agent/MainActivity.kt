package app.forgeflow.agent

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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); enableEdgeToEdge(); setContent { ForgeFlowApp() } }
}

enum class WorkspaceMode { CHAT, WORK }
enum class MessageRole { USER, ASSISTANT }
enum class TodoState { WAITING, RUNNING, DONE, FAILED }
data class MessageAttachment(val name:String, val mime:String)
data class ChatMessage(val role: MessageRole, val text: String, val createdAt: Long = System.currentTimeMillis(), val attachments:List<MessageAttachment> = emptyList(), val context:String? = null, val thinking:String? = null)
data class WorkTodo(val title: String, val state: TodoState = TodoState.WAITING)
data class WorkArtifact(val jobId: String, val name: String, val size: Long)
data class PendingAttachment(val name: String, val mime: String, val text: String? = null)
data class Conversation(val id:String=UUID.randomUUID().toString(), val title:String="Новый чат", val mode:WorkspaceMode=WorkspaceMode.CHAT, val messages:List<ChatMessage> = emptyList(), val todos:List<WorkTodo> = emptyList(), val artifact:WorkArtifact?=null, val jobId:String?=null, val updatedAt:Long=System.currentTimeMillis())
data class AppUiState(val conversations:List<Conversation> = emptyList(), val activeId:String="", val mode:WorkspaceMode=WorkspaceMode.CHAT, val input:String="", val runningIds:Set<String> = emptySet(), val drawerOpen:Boolean=false, val settingsOpen:Boolean=false, val backendUrl:String=DEFAULT_BACKEND, val deviceToken:String="", val selectedModel:String=MODEL_SUPER, val attachments:List<PendingAttachment> = emptyList(), val error:String?=null) { val active get()=conversations.firstOrNull { it.id==activeId } }

const val DEFAULT_BACKEND="https://workai-backend.lolkeckay222.workers.dev"
const val MODEL_SUPER="nvidia/nemotron-3-super-120b-a12b"
const val MODEL_ULTRA="nvidia/nemotron-3-ultra-550b-a55b"

class AgentViewModel(app:Application):AndroidViewModel(app) {
    private val prefs=app.getSharedPreferences("workai",0)
    private val client=OkHttpClient.Builder().connectTimeout(30,TimeUnit.SECONDS).readTimeout(180,TimeUnit.SECONDS).build()
    private val jobs=mutableMapOf<String,Job>()
    private val _ui=MutableStateFlow(load()); val ui:StateFlow<AppUiState> = _ui.asStateFlow()
    fun setInput(v:String){_ui.value=_ui.value.copy(input=v)}
    fun selectModel(v:String){prefs.edit().putString("model",v).apply();_ui.value=_ui.value.copy(selectedModel=v)}
    fun addAttachments(uris:List<Uri>){viewModelScope.launch(Dispatchers.IO){val resolver=getApplication<Application>().contentResolver;val added=uris.mapNotNull{uri->runCatching{val name=resolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0) else null}?:"Файл";val mime=resolver.getType(uri)?:"application/octet-stream";val text=when{name.endsWith(".mtz",true)||name.endsWith(".zip",true)->inspectArchive(uri,name);mime.startsWith("text/")||listOf(".kt",".java",".json",".xml",".md",".gradle",".properties").any{name.endsWith(it,true)}->resolver.openInputStream(uri)?.bufferedReader()?.use{it.readText().take(200_000)};else->null};PendingAttachment(name,mime,text)}.getOrNull()};withContext(Dispatchers.Main){_ui.value=_ui.value.copy(attachments=(_ui.value.attachments+added).take(8))}}}
    private fun inspectArchive(uri:Uri,name:String):String{val out=StringBuilder("Содержимое архива $name:\n");val resolver=getApplication<Application>().contentResolver;ZipInputStream(resolver.openInputStream(uri)!!).use{zip->var entry=zip.nextEntry;var total=0;val buffer=ByteArray(8192);while(entry!=null&&total<180_000){out.append("- ").append(entry.name).append(if(entry.isDirectory)"/" else "").append('\n');val lower=entry.name.lowercase();val readable=!entry.isDirectory&&(lower.endsWith(".xml")||lower.endsWith(".json")||lower.endsWith(".txt")||lower.endsWith(".html")||lower.endsWith(".css")||lower.endsWith(".js")||lower.endsWith(".properties")||lower.endsWith("manifest"));if(readable){val bytes=ByteArrayOutputStream();while(bytes.size()<32_000){val n=zip.read(buffer,0,minOf(buffer.size,32_000-bytes.size()));if(n<=0)break;bytes.write(buffer,0,n)};val value=bytes.toString(Charsets.UTF_8.name()).replace("\u0000","");out.append("```\n").append(value.take(32_000)).append("\n```\n");total+=value.length};zip.closeEntry();entry=zip.nextEntry}};return out.take(180_000)}
    fun removeAttachment(name:String){_ui.value=_ui.value.copy(attachments=_ui.value.attachments.filterNot{it.name==name})}
    fun openDrawer(v:Boolean){_ui.value=_ui.value.copy(drawerOpen=v)}
    fun openSettings(v:Boolean){_ui.value=_ui.value.copy(settingsOpen=v)}
    fun saveBackend(url:String,token:String){val clean=url.trim().trimEnd('/').ifBlank{DEFAULT_BACKEND};prefs.edit().putString("backend",clean).putString("device_token",token.trim()).apply();_ui.value=_ui.value.copy(backendUrl=clean,deviceToken=token.trim(),settingsOpen=false,error=null)}
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
        update(chat.id){it.copy(title=title,messages=it.messages+ChatMessage(MessageRole.USER,rawPrompt,attachments=cards,context=prompt),artifact=null,updatedAt=System.currentTimeMillis())}
        _ui.value=_ui.value.copy(input="",attachments=emptyList(),runningIds=_ui.value.runningIds+chat.id,error=null)
        jobs[chat.id]=viewModelScope.launch{try{if(chat.mode==WorkspaceMode.CHAT)runChat(chat.id)else runWork(chat.id,prompt)}catch(e:kotlinx.coroutines.CancellationException){update(chat.id){c->c.copy(messages=c.messages+ChatMessage(MessageRole.ASSISTANT,"Остановлено пользователем."))}}catch(e:Throwable){update(chat.id){c->c.copy(messages=c.messages+ChatMessage(MessageRole.ASSISTANT,"Не удалось получить ответ. Попробуйте ещё раз через несколько секунд."),todos=c.todos.map{if(it.state==TodoState.RUNNING)it.copy(state=TodoState.FAILED)else it})};_ui.value=_ui.value.copy(error=e.message)}finally{jobs.remove(chat.id);_ui.value=_ui.value.copy(runningIds=_ui.value.runningIds-chat.id);persist()}}
    }
    fun stop(){_ui.value.activeId.let{jobs[it]?.cancel()}}

    private suspend fun runChat(id:String){val chat=_ui.value.conversations.first{it.id==id};val a=JSONArray();chat.messages.takeLast(30).forEach{a.put(JSONObject().put("role",if(it.role==MessageRole.USER)"user" else "assistant").put("content",it.context?:it.text))};update(id){it.copy(messages=it.messages+ChatMessage(MessageRole.ASSISTANT,"",thinking="Анализирую запрос и вложения…"))};streamApi(id,JSONObject().put("messages",a).put("model",_ui.value.selectedModel))}
    private suspend fun streamApi(id:String,body:JSONObject)=withContext(Dispatchers.IO){val request=builder("/v1/chat/stream").post(body.toString().toRequestBody("application/json".toMediaType())).build();client.newCall(request).execute().use{response->if(!response.isSuccessful)error("Backend ${response.code}");val source=response.body?.source()?:error("Пустой ответ");while(!source.exhausted()){val line=source.readUtf8Line()?:continue;if(!line.startsWith("data: "))continue;val data=line.removePrefix("data: ").trim();if(data=="[DONE]")break;val event=runCatching{JSONObject(data)}.getOrNull()?:continue;val token=event.optString("token").ifEmpty{event.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()};val status=event.optString("status");if(token.isEmpty()&&status.isEmpty())continue;withContext(Dispatchers.Main){update(id){c->val list=c.messages.toMutableList();val index=list.indexOfLast{it.role==MessageRole.ASSISTANT};if(index>=0){val old=list[index];list[index]=old.copy(text=old.text+token,thinking=if(status.isNotBlank())status else if(old.text.isBlank())old.thinking else "Ответ сформирован")};c.copy(messages=list,updatedAt=System.currentTimeMillis())}}}}}

    private suspend fun runWork(id:String,prompt:String){
        val kind=when{prompt.contains("mtz",true)||prompt.contains("тема",true)->"mtz";prompt.contains("zip",true)||prompt.contains("архив",true)->"zip";else->"apk"}
        val started=api("/v1/jobs","POST",JSONObject().put("prompt",prompt).put("kind",kind));val job=started.getString("id");val t=started.optJSONArray("tasks")?:JSONArray();val todos=(0 until t.length()).map{WorkTodo(t.getString(it),if(it==0)TodoState.RUNNING else TodoState.WAITING)};update(id){it.copy(jobId=job,todos=todos)}
        repeat(300){delay(5000);val s=api("/v1/jobs/$job","GET");val sa=s.optJSONArray("steps")?:JSONArray();if(sa.length()>0){val steps=(0 until sa.length()).map{i->val x=sa.getJSONObject(i);val st=when{x.optString("conclusion")=="success"->TodoState.DONE;x.optString("conclusion")=="failure"->TodoState.FAILED;x.optString("status")=="in_progress"->TodoState.RUNNING;else->TodoState.WAITING};WorkTodo(x.optString("title","Этап ${i+1}"),st)};update(id){it.copy(todos=steps)}};if(s.optString("status")=="completed"){if(s.optString("conclusion")!="success")error("Сборка завершилась с ошибкой");val a=s.optJSONObject("artifact")?:error("Artifact не найден");update(id){it.copy(artifact=WorkArtifact(job,a.getString("name"),a.optLong("size")),messages=it.messages+ChatMessage(MessageRole.ASSISTANT,"Готово. Файл собран и проверен."),updatedAt=System.currentTimeMillis())};return}}
        error("Превышено время ожидания сборки")
    }

    fun downloadArtifact(a:WorkArtifact,share:Boolean){viewModelScope.launch{try{val uri=withContext(Dispatchers.IO){client.newCall(builder("/v1/jobs/${a.jobId}/download").get().build()).execute().use{r->if(!r.isSuccessful)error("Download ${r.code}");val values=ContentValues().apply{put(MediaStore.Downloads.DISPLAY_NAME,a.name);put(MediaStore.Downloads.MIME_TYPE,if(a.name.endsWith(".apk"))"application/vnd.android.package-archive" else "application/octet-stream");put(MediaStore.Downloads.IS_PENDING,1)};val resolver=getApplication<Application>().contentResolver;val u=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values)?:error("Не удалось создать файл");resolver.openOutputStream(u)!!.use{o->r.body!!.byteStream().copyTo(o)};values.clear();values.put(MediaStore.Downloads.IS_PENDING,0);resolver.update(u,values,null,null);u}};_ui.value=_ui.value.copy(error="Сохранено: ${a.name}");if(share){val i=Intent(Intent.ACTION_SEND).apply{type=if(a.name.endsWith(".apk"))"application/vnd.android.package-archive" else "application/octet-stream";putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)};getApplication<Application>().startActivity(Intent.createChooser(i,"Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}}catch(e:Throwable){_ui.value=_ui.value.copy(error=e.message)}}}
    private fun builder(path:String)=Request.Builder().url(_ui.value.backendUrl+path).header("X-WorkAI-Token",_ui.value.deviceToken).header("Accept","application/json")
    private suspend fun api(path:String,method:String,body:JSONObject?=null):JSONObject=withContext(Dispatchers.IO){val b=builder(path);if(method=="POST")b.post((body?:JSONObject()).toString().toRequestBody("application/json".toMediaType()))else b.get();client.newCall(b.build()).execute().use{r->val text=r.body?.string().orEmpty();if(!r.isSuccessful)error("Backend ${r.code}: ${runCatching{JSONObject(text).optString("detail",text)}.getOrDefault(text).take(400)}");JSONObject(text)}}
    private fun update(id:String,f:(Conversation)->Conversation){_ui.value=_ui.value.copy(conversations=_ui.value.conversations.map{if(it.id==id)f(it)else it});persist()}
    private fun persist(){val root=JSONArray();_ui.value.conversations.forEach{c->val m=JSONArray();c.messages.forEach{msg->val files=JSONArray();msg.attachments.forEach{files.put(JSONObject().put("name",it.name).put("mime",it.mime))};m.put(JSONObject().put("role",msg.role.name).put("text",msg.text).put("at",msg.createdAt).put("files",files))};root.put(JSONObject().put("id",c.id).put("title",c.title).put("mode",c.mode.name).put("updated",c.updatedAt).put("messages",m))};prefs.edit().putString("chats",root.toString()).putString("active",_ui.value.activeId).apply()}
    private fun load():AppUiState{val saved=mutableListOf<Conversation>();runCatching{val a=JSONArray(prefs.getString("chats","[]"));for(i in 0 until a.length()){val o=a.getJSONObject(i);val m=mutableListOf<ChatMessage>();val ma=o.optJSONArray("messages")?:JSONArray();for(j in 0 until ma.length())ma.getJSONObject(j).let{m+=ChatMessage(MessageRole.valueOf(it.getString("role")),it.getString("text"),it.optLong("at"))};saved+=Conversation(id=o.getString("id"),title=o.getString("title"),mode=WorkspaceMode.valueOf(o.getString("mode")),messages=m,updatedAt=o.optLong("updated"))}};val chats=saved.ifEmpty{listOf(Conversation())};val active=prefs.getString("active",null)?.takeIf{id->chats.any{it.id==id}}?:chats.first().id;val selected=chats.first{it.id==active};return AppUiState(conversations=chats,activeId=active,mode=selected.mode,backendUrl=prefs.getString("backend",DEFAULT_BACKEND)?:DEFAULT_BACKEND,deviceToken=prefs.getString("device_token","").orEmpty(),selectedModel=prefs.getString("model",MODEL_SUPER)?:MODEL_SUPER)}
}
