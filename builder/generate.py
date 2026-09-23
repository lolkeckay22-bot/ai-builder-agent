import argparse, base64, hashlib, json, os, re, shutil, time, urllib.request, zipfile, xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path("generated")
MODEL = os.environ.get("MODEL") or "nvidia/nemotron-3-super-120b-a12b"
INPUT = Path("input")
SKILL_NAMES={"file-creator","file-analysis","archive-editor","mtz-editor","android-app-builder","kwgt-editor","web-research","multi-agent"}
COMPLETED_PHASES=set()

def complete_phase(phase):
    if phase in COMPLETED_PHASES: return
    tasks=json.loads(os.environ.get("TASKS") or "[]")
    for index,task in enumerate(tasks):
        if isinstance(task,dict) and task.get("phase")==phase:
            emit_event("todo.updated",index=index,state="DONE")
            COMPLETED_PHASES.add(phase)

def selected_skill_text():
    requested=json.loads(os.environ.get("SKILLS") or "[]")
    if not isinstance(requested,list): raise ValueError("Invalid SKILLS payload")
    sections=[]
    for name in requested[:5]:
        if name not in SKILL_NAMES: continue
        path=Path("skills")/name/"SKILL.md"
        if path.is_file():
            contents=path.read_text(encoding="utf-8")[:12000]
            sections.append(f"[{name} SKILL.md]\n{contents}")
            emit_event("file.read",path=str(path),label=f"Прочитан навык {name}",icon="file")
    return "\n\n".join(sections)

def emit_event(event_type, **data):
    origin=os.environ.get("WORKAI_CALLBACK_ORIGIN","").rstrip("/")
    token=os.environ.get("WORKAI_DEVICE_TOKEN","")
    job=os.environ.get("JOB_ID","")
    if not origin or not token or not job: return
    if not origin.startswith("https://"): raise ValueError("Invalid callback origin")
    payload=json.dumps({"type":event_type,**data},ensure_ascii=False).encode()
    request=urllib.request.Request(f"{origin}/v1/jobs/{job}/events",data=payload,headers={"X-WorkAI-Token":token,"Content-Type":"application/json"})
    with urllib.request.urlopen(request,timeout=20) as response:
        if response.status!=201: raise RuntimeError(f"Event rejected: {response.status}")

def fetch_attachments():
    INPUT.mkdir(exist_ok=True)
    items = json.loads(os.environ.get("ATTACHMENTS") or "[]")
    token, repo = os.environ.get("GH_TOKEN", ""), os.environ.get("GITHUB_REPOSITORY", "")
    for item in items:
        name = Path(str(item.get("name") or "file.bin")).name
        chunks = item.get("chunks") or []
        whole = hashlib.sha256()
        total = 0
        with (INPUT/name).open("wb") as output:
            for expected_index, chunk in enumerate(chunks):
                if int(chunk.get("index", -1)) != expected_index: raise ValueError(f"Chunk order mismatch: {name}")
                sha = str(chunk.get("sha") or "")
                req = urllib.request.Request(f"https://api.github.com/repos/{repo}/git/blobs/{sha}", headers={"Authorization":f"Bearer {token}","Accept":"application/vnd.github+json","User-Agent":"WorkAI-Builder"})
                with urllib.request.urlopen(req, timeout=120) as response: payload=json.load(response)
                data = base64.b64decode(payload["content"], validate=True)
                if len(data) != int(chunk.get("size", -1)) or hashlib.sha256(data).hexdigest() != chunk.get("sha256"): raise ValueError(f"Chunk integrity failed: {name} part {expected_index + 1}")
                output.write(data); whole.update(data); total += len(data)
        if total != int(item.get("size", -1)) or whole.hexdigest() != item.get("sha256"): raise ValueError(f"Attachment integrity failed: {name}")
        emit_event("file.read",path=f"input/{name}",size=total,label=f"Получен и проверен {name}")
    if items: complete_phase("inspect")

def call_ai(system, user, max_tokens=6000):
    agnes = MODEL.startswith("agnes-")
    cohere = MODEL == "north-mini-code-1-0"
    zen = MODEL in {"nemotron-3-ultra-free","mimo-v2.6-flash-free","muse-spark-1.3-contributor-free","muse-spark-1.2-contributor-free"}
    responses = MODEL.startswith("muse-spark-")
    endpoint = "https://opencode.ai/zen/v1/responses" if responses else "https://opencode.ai/zen/v1/chat/completions" if zen else "https://api.cohere.com/compatibility/v1/chat/completions" if cohere else "https://apihub.agnes-ai.com/v1/chat/completions" if agnes else "https://integrate.api.nvidia.com/v1/chat/completions"
    key = os.environ.get("OPENCODE_API_KEY") if zen else os.environ.get("COHERE_API_KEY") if cohere else os.environ.get("AGNES_API_KEY") if agnes else os.environ.get("NVIDIA_API_KEY")
    model = MODEL
    if not key: raise RuntimeError(f"{('OpenCode Zen' if zen else 'Cohere' if cohere else 'Agnes' if agnes else 'NVIDIA')}_API_KEY is not configured")
    emit_event("tool.started",name="model.request",label=f"Запрос к {MODEL}",icon="code")
    skill_text=selected_skill_text()
    messages=[{"role":"system","content":system+("\n\nНавыки для этой задачи:\n"+skill_text if skill_text else "")},{"role":"user","content":user}]
    payload = json.dumps({"model":model,"input":messages,"max_output_tokens":max_tokens,"stream":False} if responses else {"model": model, "messages":messages, "temperature":0.35, "max_tokens":max_tokens, "stream":False}).encode()
    req = urllib.request.Request(endpoint, data=payload, headers={"Authorization":f"Bearer {key}", "Content-Type":"application/json", "Accept":"application/json"})
    failure = None
    for attempt in range(4):
      try:
        with urllib.request.urlopen(req, timeout=180) as response:
            data=json.load(response)
            answer=(data.get("output_text") or "".join(part.get("text","") for item in data.get("output",[]) for part in item.get("content",[]))) if responses else data["choices"][0]["message"]["content"]
            emit_event("tool.completed",name="model.request",label=f"Получен ответ {MODEL}",icon="code")
            return answer
      except Exception as error:
        failure = error
        if attempt < 3: time.sleep(2 ** attempt)
    emit_event("tool.failed",name="model.request",label=f"Ошибка {MODEL}: {failure}",icon="error")
    raise RuntimeError(f"{MODEL} request failed: {failure}") from failure

def run_subagents(prompt, task_type):
    roles = [
        ("planner", "Разбей задачу на проверяемые требования, риски и критерии готовности."),
        ("specialist", "Предложи техническую реализацию. Учитывай формат файлов, совместимость и крайние случаи."),
        ("reviewer", "Независимо найди вероятные ошибки, потери данных и проверки, которые обязательны перед выдачей результата."),
    ]
    def ask(role, instruction):
        return role, call_ai(f"Ты независимый саб-агент WorkAI ({role}). {instruction} Верни компактный технический отчёт, не обращайся к пользователю.", f"Тип задачи: {task_type}\nЗапрос:\n{prompt}", 1800)
    reports = {}
    with ThreadPoolExecutor(max_workers=len(roles)) as pool:
        futures = [pool.submit(ask, *role) for role in roles]
        for future in as_completed(futures):
            try:
                role, report = future.result(); reports[role] = report
            except Exception as error:
                reports[roles[len(reports)][0] if len(reports)<len(roles) else "agent"] = f"Недоступен: {error}"
    return "\n\n".join(f"[{role}]\n{reports.get(role, '')}" for role, _ in roles)

def object_from(text):
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start: raise ValueError("Model did not return JSON")
    return json.loads(text[start:end+1])

def safe_package(value):
    value = re.sub(r"[^a-zA-Z0-9_.]", "", value or "app.workai.generated").lower()
    return value if "." in value else "app.workai.generated"

def android_template(prompt):
    system = '''Ты Android-разработчик. Создай небольшое, но реально работающее приложение Jetpack Compose по запросу. Верни только JSON без markdown с полями app_name, package_name и main_activity. main_activity — полный Kotlin-файл MainActivity.kt. Используй только Compose Material3, core-ktx и activity-compose. Никаких сторонних библиотек, WebView, ресурсов drawable и XML. compileSdk 35, minSdk 26. Код обязан компилироваться.'''
    advice = run_subagents(prompt, "Android APK")
    spec = object_from(call_ai(system, f"ЗАПРОС:\n{prompt}\n\nОТЧЁТЫ САБ-АГЕНТОВ:\n{advice}"))
    complete_phase("inspect")
    app_name = str(spec.get("app_name") or "WorkAI Result")[:40]
    package = safe_package(spec.get("package_name"))
    main = str(spec.get("main_activity") or "")
    if "class MainActivity" not in main: raise ValueError("Invalid MainActivity")
    main = re.sub(r"^package\s+[\w.]+", f"package {package}", main, count=1, flags=re.M)
    ROOT.mkdir(exist_ok=True)
    (ROOT/"settings.gradle.kts").write_text('pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }\ndependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }\nrootProject.name="WorkAIResult"\ninclude(":app")\n')
    (ROOT/"build.gradle.kts").write_text('plugins { id("com.android.application") version "8.7.3" apply false; id("org.jetbrains.kotlin.android") version "2.1.0" apply false; id("org.jetbrains.kotlin.plugin.compose") version "2.1.0" apply false }\n')
    app = ROOT/"app"; (app/"src/main/java"/Path(package.replace(".","/"))).mkdir(parents=True, exist_ok=True)
    (app/"build.gradle.kts").write_text(f'''plugins {{ id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }}
android {{ namespace="{package}"; compileSdk=35
 defaultConfig {{ applicationId="{package}"; minSdk=26; targetSdk=35; versionCode=1; versionName="1.0" }}
 compileOptions {{ sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }}
 buildFeatures {{ compose=true }}
}}
kotlin {{ jvmToolchain(17) }}
dependencies {{ val bom=platform("androidx.compose:compose-bom:2025.01.00"); implementation(bom); implementation("androidx.core:core-ktx:1.15.0"); implementation("androidx.activity:activity-compose:1.10.0"); implementation("androidx.compose.material3:material3"); implementation("androidx.compose.ui:ui"); implementation("androidx.compose.ui:ui-tooling-preview"); debugImplementation("androidx.compose.ui:ui-tooling") }}
''')
    (app/"src/main/AndroidManifest.xml").write_text(f'''<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:theme="@style/AppTheme" android:label="{app_name}"><activity android:name=".MainActivity" android:exported="true"><intent-filter><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent-filter></activity></application></manifest>''')
    values=app/"src/main/res/values"; values.mkdir(parents=True, exist_ok=True)
    (values/"styles.xml").write_text('<resources><style name="AppTheme" parent="android:style/Theme.Material.NoActionBar"><item name="android:fontFamily">sans</item><item name="android:windowLightStatusBar">false</item><item name="android:colorAccent">#10A37F</item></style></resources>')
    (app/"src/main/java"/Path(package.replace(".","/"))/"MainActivity.kt").write_text(main)
    emit_event("file.write",path="generated/app/src/main/java/MainActivity.kt",label="Создан код приложения",icon="code")
    complete_phase("modify")
    return app_name

def repair(log):
    files = list(ROOT.glob("app/src/main/java/**/*.kt"))
    target = files[0]
    current = target.read_text()
    system = "Исправь Kotlin Jetpack Compose файл по логу компилятора. Верни только полный исправленный Kotlin-файл без markdown. Не добавляй сторонние зависимости."
    fixed = call_ai(system, f"ЛОГ:\n{log[-12000:]}\n\nФАЙЛ:\n{current}", 6000).strip()
    fixed = re.sub(r"^```(?:kotlin)?\s*|\s*```$", "", fixed, flags=re.S)
    target.write_text(fixed)

def confined(root, relative):
    target=(root/str(relative)).resolve()
    if target == root.resolve() or root.resolve() not in target.parents:
        raise ValueError(f"Unsafe archive path: {relative}")
    return target

def make_archive(prompt, job_id, kind):
    out = Path("output"); out.mkdir(exist_ok=True)
    source = next((p for p in INPUT.iterdir() if p.suffix.lower() in (".mtz", ".zip")), None) if INPUT.exists() else None
    if source:
        folder=Path("archive_work"); shutil.rmtree(folder,ignore_errors=True); folder.mkdir()
        emit_event("tool.started",name="extract_archive",label=f"Распаковываю {source.name}",icon="archive")
        with zipfile.ZipFile(source) as z:
            for info in z.infolist():
                target=confined(folder, info.filename)
                if info.is_dir(): target.mkdir(parents=True,exist_ok=True)
                else:
                    target.parent.mkdir(parents=True,exist_ok=True)
                    with z.open(info) as src, target.open("wb") as dst: shutil.copyfileobj(src,dst)
        emit_event("tool.completed",name="extract_archive",label=f"Распакован {source.name}",icon="archive")
        complete_phase("inspect")
        tree=[]
        for p in folder.rglob("*"):
            if p.is_file():
                rel=p.relative_to(folder).as_posix(); row={"path":rel,"size":p.stat().st_size}
                if p.suffix.lower() in (".xml",".json",".txt",".md",".html",".css",".js",".properties") and p.stat().st_size<120000:
                    row["content"]=p.read_text(errors="ignore")[:30000]
                    emit_event("file.read",path=rel,label=f"Прочитан {rel}",icon="file")
                tree.append(row)
        system='''Ты редактор ZIP/MTZ. Верни только JSON: {"edits":[{"path":"путь","content":"полное новое содержимое"}],"deletes":["путь"]}. Меняй только то, что требуется. Не выдумывай бинарные файлы и не используй ../. Для MTZ сохраняй совместимость HyperOS/MIUI.'''
        advice=run_subagents(prompt, f"редактирование {kind.upper()}")
        plan=object_from(call_ai(system,f"ЗАДАЧА:\n{prompt}\n\nОТЧЁТЫ САБ-АГЕНТОВ:\n{advice}\n\nФАЙЛЫ:\n{json.dumps(tree,ensure_ascii=False)[:100000]}",6000))
        for rel in plan.get("deletes",[]):
            target=confined(folder, rel)
            if target.is_file(): target.unlink(); emit_event("tool.completed",name="delete",label=f"Удалён {rel}",icon="delete")
        for edit in plan.get("edits",[]):
            target=confined(folder, edit.get("path", ""))
            target.parent.mkdir(parents=True,exist_ok=True); target.write_text(str(edit.get("content","")))
            emit_event("file.write",path=str(edit.get("path","")),label=f"Изменён {edit.get('path','')}",icon="edit")
        if plan.get("edits") or plan.get("deletes"): complete_phase("modify")
        if kind=="mtz":
            if not (folder/"description.xml").exists(): (folder/"description.xml").write_text('<?xml version="1.0" encoding="UTF-8"?><MIUI-Theme><title>WorkAI Theme</title><designer>WorkAI</designer><version>1.0</version><uiVersion>14</uiVersion></MIUI-Theme>')
            if not (folder/"theme_values.xml").exists(): (folder/"theme_values.xml").write_text('<?xml version="1.0" encoding="UTF-8"?><MIUI_Theme_Values></MIUI_Theme_Values>')
        suffix=".mtz" if kind=="mtz" else ".zip"
        emit_event("tool.started",name="create_archive",label="Собираю архив",icon="archive")
        with zipfile.ZipFile(out/f"WorkAI-{job_id}{suffix}","w",zipfile.ZIP_DEFLATED) as z:
            for p in folder.rglob("*"):
                if p.is_file(): z.write(p,p.relative_to(folder))
        emit_event("tool.completed",name="create_archive",label="Архив собран",icon="archive")
        complete_phase("build")
        return
    advice=run_subagents(prompt, f"создание {kind.upper()} архива")
    raw = object_from(call_ai('''Ты файловый агент WorkAI. Создай содержимое архива по запросу. Верни только JSON: {"archive_name":"имя без пути","files":[{"path":"безопасный/путь.txt","content":"полное содержимое"}]}. Добавь все явно запрошенные файлы. Если запрошен MTZ как тема HyperOS, создай валидные description.xml, theme_values.xml и README.txt. Никогда не отвечай, что не умеешь создавать файл.''', f"ЗАПРОС:\n{prompt}\n\nОТЧЁТЫ САБ-АГЕНТОВ:\n{advice}", 4000))
    complete_phase("inspect")
    folder=Path("archive_new"); shutil.rmtree(folder,ignore_errors=True); folder.mkdir(exist_ok=True)
    for item in raw.get("files", [])[:100]:
        target=confined(folder, item.get("path") or "README.txt")
        target.parent.mkdir(parents=True,exist_ok=True);target.write_text(str(item.get("content") or ""))
        emit_event("file.write",path=str(item.get("path") or "README.txt"),label=f"Создан {item.get('path') or 'README.txt'}",icon="file")
    if not any(p.is_file() for p in folder.rglob("*")): raise ValueError("Model returned no files")
    complete_phase("modify")
    if kind=="mtz":
        if not (folder/"description.xml").exists(): (folder/"description.xml").write_text('<?xml version="1.0" encoding="UTF-8"?><MIUI-Theme><title>WorkAI Theme</title><designer>WorkAI</designer><version>1.0</version><uiVersion>14</uiVersion></MIUI-Theme>')
        if not (folder/"theme_values.xml").exists(): (folder/"theme_values.xml").write_text('<?xml version="1.0" encoding="UTF-8"?><MIUI_Theme_Values></MIUI_Theme_Values>')
    suffix=".mtz" if kind=="mtz" else ".zip"
    requested=re.sub(r"[^\w .-]","_",str(raw.get("archive_name") or f"WorkAI-{job_id}")).strip(" .") or f"WorkAI-{job_id}"
    requested=re.sub(r"\.(rar|zip|mtz)$","",requested,flags=re.I)
    with zipfile.ZipFile(out/f"{requested}{suffix}","w",zipfile.ZIP_DEFLATED) as z:
        for p in folder.rglob("*"): z.write(p,p.relative_to(folder))
    emit_event("tool.completed",name="create_archive",label=f"Собран {requested}{suffix}",icon="archive")
    complete_phase("build")

def verify_artifact(kind):
    emit_event("tool.started",name="verify_artifact",label="Проверяю итоговый файл",icon="file")
    out=Path("output")
    suffix=".apk" if kind=="apk" else ".mtz" if kind=="mtz" else ".zip"
    files=[p for p in out.glob(f"*{suffix}") if p.is_file()]
    if len(files)!=1: raise ValueError(f"Expected one {suffix} artifact, found {len(files)}")
    artifact=files[0]
    if artifact.stat().st_size<=100: raise ValueError("Artifact is empty or too small")
    if kind in ("mtz","zip"):
        with zipfile.ZipFile(artifact) as z:
            bad=z.testzip()
            if bad: raise ValueError(f"Corrupt archive member: {bad}")
            names={n.rstrip("/") for n in z.namelist() if not n.endswith("/")}
            if not names: raise ValueError("Archive contains no files")
            if kind=="mtz":
                required={"description.xml","theme_values.xml"}
                missing=required-names
                if missing: raise ValueError(f"MTZ missing required files: {', '.join(sorted(missing))}")
                for name in required:
                    data=z.read(name)
                    if not data.strip(): raise ValueError(f"MTZ file is empty: {name}")
                    ET.fromstring(data)
                prompt=os.environ.get("PROMPT","")
                rename=re.search(r"(?:замени|изменить|поменяй)\s+название\s+темы\s+на\s+[\"'«]?([^\n,.\"'»]+)",prompt,re.I)
                if rename:
                    expected=rename.group(1).strip()
                    root=ET.fromstring(z.read("description.xml"))
                    actual=(root.findtext("title") or "").strip()
                    if actual!=expected: raise ValueError(f"MTZ title mismatch: expected {expected!r}, got {actual!r}")
    print(json.dumps({"artifact":artifact.name,"size":artifact.stat().st_size,"sha256":hashlib.sha256(artifact.read_bytes()).hexdigest()}))
    emit_event("tool.completed",name="verify_artifact",label=f"Проверен {artifact.name}",icon="file")
    complete_phase("verify")

def main():
    p=argparse.ArgumentParser(); p.add_argument("command",choices=["fetch","generate","repair","verify","publish","build-complete"]); p.add_argument("--prompt",default=""); p.add_argument("--kind",default="apk"); p.add_argument("--job",default="job"); p.add_argument("--log",default="build.log"); a=p.parse_args()
    if a.command=="publish": complete_phase("publish"); return
    if a.command=="build-complete":
        emit_event("tool.completed",name="build_apk",label="APK собран",icon="build")
        complete_phase("build"); return
    if a.command=="fetch": fetch_attachments(); return
    if a.command=="repair": repair(Path(a.log).read_text(errors="ignore")); return
    if a.command=="verify": verify_artifact(a.kind); return
    if a.kind=="apk":
        name=android_template(a.prompt); Path("app_name.txt").write_text(name)
    else: make_archive(a.prompt,a.job,a.kind)
if __name__=="__main__": main()
