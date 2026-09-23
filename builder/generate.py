import argparse, base64, hashlib, json, os, re, shutil, time, urllib.request, zipfile, xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path("generated")
MODEL = os.environ.get("MODEL") or "nvidia/nemotron-3-super-120b-a12b"
INPUT = Path("input")

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

def call_ai(system, user, max_tokens=6000):
    agnes = MODEL.startswith("agnes-")
    cohere = MODEL == "north-mini-code-1-0"
    endpoint = "https://api.cohere.com/compatibility/v1/chat/completions" if cohere else "https://apihub.agnes-ai.com/v1/chat/completions" if agnes else "https://integrate.api.nvidia.com/v1/chat/completions"
    key = os.environ.get("COHERE_API_KEY") if cohere else os.environ.get("AGNES_API_KEY") if agnes else os.environ.get("NVIDIA_API_KEY")
    model = MODEL
    if (agnes or cohere) and not key:
        endpoint, key, model = "https://integrate.api.nvidia.com/v1/chat/completions", os.environ.get("NVIDIA_API_KEY"), "nvidia/nemotron-3-super-120b-a12b"
    if not key: raise RuntimeError("AI provider key is not configured")
    payload = json.dumps({"model": model, "messages": [{"role":"system","content":system},{"role":"user","content":user}], "temperature":0.35, "max_tokens":max_tokens, "stream":False}).encode()
    req = urllib.request.Request(endpoint, data=payload, headers={"Authorization":f"Bearer {key}", "Content-Type":"application/json", "Accept":"application/json"})
    failure = None
    for attempt in range(4):
      try:
        with urllib.request.urlopen(req, timeout=180) as response:
            return json.load(response)["choices"][0]["message"]["content"]
      except Exception as error:
        failure = error
        if attempt < 3: time.sleep(2 ** attempt)
    try:
        if not (agnes or cohere) or endpoint.startswith("https://integrate.api.nvidia.com"): raise failure
        fallback_key = os.environ.get("NVIDIA_API_KEY")
        if not fallback_key: raise
        fallback = json.dumps({"model":"nvidia/nemotron-3-super-120b-a12b","messages":[{"role":"system","content":system},{"role":"user","content":user}],"temperature":0.35,"max_tokens":max_tokens,"stream":False}).encode()
        request = urllib.request.Request("https://integrate.api.nvidia.com/v1/chat/completions", data=fallback, headers={"Authorization":f"Bearer {fallback_key}","Content-Type":"application/json","Accept":"application/json"})
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.load(response)["choices"][0]["message"]["content"]
    except Exception:
        raise failure

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
    return app_name

def repair(log):
    files = list(ROOT.glob("app/src/main/java/**/*.kt"))
    target = files[0]
    current = target.read_text()
    system = "Исправь Kotlin Jetpack Compose файл по логу компилятора. Верни только полный исправленный Kotlin-файл без markdown. Не добавляй сторонние зависимости."
    fixed = call_ai(system, f"ЛОГ:\n{log[-12000:]}\n\nФАЙЛ:\n{current}", 6000).strip()
    fixed = re.sub(r"^```(?:kotlin)?\s*|\s*```$", "", fixed, flags=re.S)
    target.write_text(fixed)

def make_archive(prompt, job_id, kind):
    out = Path("output"); out.mkdir(exist_ok=True)
    source = next((p for p in INPUT.iterdir() if p.suffix.lower() in (".mtz", ".zip")), None) if INPUT.exists() else None
    if source:
        folder=Path("archive_work"); shutil.rmtree(folder,ignore_errors=True); folder.mkdir()
        with zipfile.ZipFile(source) as z:
            for info in z.infolist():
                target=(folder/info.filename).resolve()
                if str(target).startswith(str(folder.resolve())): z.extract(info,folder)
        tree=[]
        for p in folder.rglob("*"):
            if p.is_file():
                rel=p.relative_to(folder).as_posix(); row={"path":rel,"size":p.stat().st_size}
                if p.suffix.lower() in (".xml",".json",".txt",".md",".html",".css",".js",".properties") and p.stat().st_size<120000:
                    row["content"]=p.read_text(errors="ignore")[:30000]
                tree.append(row)
        system='''Ты редактор ZIP/MTZ. Верни только JSON: {"edits":[{"path":"путь","content":"полное новое содержимое"}],"deletes":["путь"]}. Меняй только то, что требуется. Не выдумывай бинарные файлы и не используй ../. Для MTZ сохраняй совместимость HyperOS/MIUI.'''
        advice=run_subagents(prompt, f"редактирование {kind.upper()}")
        plan=object_from(call_ai(system,f"ЗАДАЧА:\n{prompt}\n\nОТЧЁТЫ САБ-АГЕНТОВ:\n{advice}\n\nФАЙЛЫ:\n{json.dumps(tree,ensure_ascii=False)[:100000]}",6000))
        for rel in plan.get("deletes",[]):
            target=(folder/str(rel)).resolve()
            if str(target).startswith(str(folder.resolve())) and target.is_file(): target.unlink()
        for edit in plan.get("edits",[]):
            target=(folder/str(edit.get("path", ""))).resolve()
            if str(target).startswith(str(folder.resolve())):
                target.parent.mkdir(parents=True,exist_ok=True); target.write_text(str(edit.get("content","")))
        if kind=="mtz":
            if not (folder/"description.xml").exists(): (folder/"description.xml").write_text('<?xml version="1.0" encoding="UTF-8"?><MIUI-Theme><title>WorkAI Theme</title><designer>WorkAI</designer><version>1.0</version><uiVersion>14</uiVersion></MIUI-Theme>')
            if not (folder/"theme_values.xml").exists(): (folder/"theme_values.xml").write_text('<?xml version="1.0" encoding="UTF-8"?><MIUI_Theme_Values></MIUI_Theme_Values>')
        suffix=".mtz" if kind=="mtz" else ".zip"
        with zipfile.ZipFile(out/f"WorkAI-{job_id}{suffix}","w",zipfile.ZIP_DEFLATED) as z:
            for p in folder.rglob("*"):
                if p.is_file(): z.write(p,p.relative_to(folder))
        return
    advice=run_subagents(prompt, f"создание {kind.upper()} архива")
    raw = object_from(call_ai('''Ты файловый агент WorkAI. Создай содержимое архива по запросу. Верни только JSON: {"archive_name":"имя без пути","files":[{"path":"безопасный/путь.txt","content":"полное содержимое"}]}. Добавь все явно запрошенные файлы. Если запрошен MTZ как тема HyperOS, создай валидные description.xml, theme_values.xml и README.txt. Никогда не отвечай, что не умеешь создавать файл.''', f"ЗАПРОС:\n{prompt}\n\nОТЧЁТЫ САБ-АГЕНТОВ:\n{advice}", 4000))
    folder=Path("archive_new"); shutil.rmtree(folder,ignore_errors=True); folder.mkdir(exist_ok=True)
    for item in raw.get("files", [])[:100]:
        target=(folder/str(item.get("path") or "README.txt")).resolve()
        if str(target).startswith(str(folder.resolve())):
            target.parent.mkdir(parents=True,exist_ok=True);target.write_text(str(item.get("content") or ""))
    if not any(folder.rglob("*")): (folder/"README.txt").write_text(prompt)
    if kind=="mtz":
        if not (folder/"description.xml").exists(): (folder/"description.xml").write_text('<?xml version="1.0" encoding="UTF-8"?><MIUI-Theme><title>WorkAI Theme</title><designer>WorkAI</designer><version>1.0</version><uiVersion>14</uiVersion></MIUI-Theme>')
        if not (folder/"theme_values.xml").exists(): (folder/"theme_values.xml").write_text('<?xml version="1.0" encoding="UTF-8"?><MIUI_Theme_Values></MIUI_Theme_Values>')
    suffix=".mtz" if kind=="mtz" else ".zip"
    requested=re.sub(r"[^\w .-]","_",str(raw.get("archive_name") or f"WorkAI-{job_id}")).strip(" .") or f"WorkAI-{job_id}"
    requested=re.sub(r"\.(rar|zip|mtz)$","",requested,flags=re.I)
    with zipfile.ZipFile(out/f"{requested}{suffix}","w",zipfile.ZIP_DEFLATED) as z:
        for p in folder.rglob("*"): z.write(p,p.relative_to(folder))

def verify_artifact(kind):
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
    print(json.dumps({"artifact":artifact.name,"size":artifact.stat().st_size,"sha256":hashlib.sha256(artifact.read_bytes()).hexdigest()}))

def main():
    p=argparse.ArgumentParser(); p.add_argument("command",choices=["fetch","generate","repair","verify"]); p.add_argument("--prompt",default=""); p.add_argument("--kind",default="apk"); p.add_argument("--job",default="job"); p.add_argument("--log",default="build.log"); a=p.parse_args()
    if a.command=="fetch": fetch_attachments(); return
    if a.command=="repair": repair(Path(a.log).read_text(errors="ignore")); return
    if a.command=="verify": verify_artifact(a.kind); return
    if a.kind=="apk":
        name=android_template(a.prompt); Path("app_name.txt").write_text(name)
    else: make_archive(a.prompt,a.job,a.kind)
if __name__=="__main__": main()
