import argparse, json, os, re, urllib.request, zipfile
from pathlib import Path

ROOT = Path("generated")
MODEL = "nvidia/nemotron-3-super-120b-a12b"

def call_ai(system, user, max_tokens=6000):
    payload = json.dumps({"model": MODEL, "messages": [{"role":"system","content":system},{"role":"user","content":user}], "temperature":0.35, "max_tokens":max_tokens, "stream":False}).encode()
    req = urllib.request.Request("https://integrate.api.nvidia.com/v1/chat/completions", data=payload, headers={"Authorization":f"Bearer {os.environ['NVIDIA_API_KEY']}", "Content-Type":"application/json", "Accept":"application/json"})
    with urllib.request.urlopen(req, timeout=180) as response:
        return json.load(response)["choices"][0]["message"]["content"]

def object_from(text):
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start: raise ValueError("Model did not return JSON")
    return json.loads(text[start:end+1])

def safe_package(value):
    value = re.sub(r"[^a-zA-Z0-9_.]", "", value or "app.workai.generated").lower()
    return value if "." in value else "app.workai.generated"

def android_template(prompt):
    system = '''Ты Android-разработчик. Создай небольшое, но реально работающее приложение Jetpack Compose по запросу. Верни только JSON без markdown с полями app_name, package_name и main_activity. main_activity — полный Kotlin-файл MainActivity.kt. Используй только Compose Material3, core-ktx и activity-compose. Никаких сторонних библиотек, WebView, ресурсов drawable и XML. compileSdk 35, minSdk 26. Код обязан компилироваться.'''
    spec = object_from(call_ai(system, prompt))
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

def make_mtz(prompt, job_id):
    out = Path("output"); out.mkdir(exist_ok=True)
    raw = object_from(call_ai('Верни только JSON для темы HyperOS: {"name":"...","author":"WorkAI","primary":"#RRGGBB","secondary":"#RRGGBB","description":"..."}.', prompt, 900))
    folder=Path("mtz"); folder.mkdir(exist_ok=True)
    (folder/"description.xml").write_text(f'''<?xml version="1.0" encoding="UTF-8"?><MIUI-Theme><title>{raw.get("name","WorkAI Theme")}</title><designer>WorkAI</designer><author>{raw.get("author","WorkAI")}</author><version>1.0</version><uiVersion>14</uiVersion></MIUI-Theme>''')
    (folder/"theme_values.xml").write_text(f'''<MIUI_Theme_Values><color name="workai_primary">{raw.get("primary","#10A37F")}</color><color name="workai_secondary">{raw.get("secondary","#171719")}</color></MIUI_Theme_Values>''')
    (folder/"README.txt").write_text(str(raw.get("description", prompt)))
    with zipfile.ZipFile(out/f"WorkAI-{job_id}.mtz","w",zipfile.ZIP_DEFLATED) as z:
        for p in folder.rglob("*"): z.write(p,p.relative_to(folder))

def main():
    p=argparse.ArgumentParser(); p.add_argument("command",choices=["generate","repair"]); p.add_argument("--prompt",default=""); p.add_argument("--kind",default="apk"); p.add_argument("--job",default="job"); p.add_argument("--log",default="build.log"); a=p.parse_args()
    if a.command=="repair": repair(Path(a.log).read_text(errors="ignore")); return
    if a.kind=="apk":
        name=android_template(a.prompt); Path("app_name.txt").write_text(name)
    else: make_mtz(a.prompt,a.job)
if __name__=="__main__": main()
