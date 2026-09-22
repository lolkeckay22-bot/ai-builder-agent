const JSON_HEADERS = { "content-type": "application/json; charset=utf-8" };

function json(data, status = 200, extra = {}) {
  return new Response(JSON.stringify(data), { status, headers: { ...JSON_HEADERS, ...extra } });
}

function cors(request) {
  const origin = request.headers.get("origin") || "*";
  return {
    "access-control-allow-origin": origin,
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type,x-workai-token",
    "access-control-max-age": "86400",
  };
}

function authorized(request, env) {
  const supplied = request.headers.get("x-workai-token") || "";
  return supplied.length > 20 && supplied === env.WORKAI_DEVICE_TOKEN;
}

const ALLOWED_MODELS = new Set([
  "nvidia/nemotron-3-super-120b-a12b",
  "nvidia/nemotron-3-ultra-550b-a55b",
]);

const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

async function nvidia(env, messages, maxTokens = 2048, temperature = 0.45, requestedModel) {
  const model = ALLOWED_MODELS.has(requestedModel) ? requestedModel : (env.NVIDIA_MODEL || "nvidia/nemotron-3-super-120b-a12b");
  let lastStatus = 0;
  let lastText = "";
  for (let attempt = 0; attempt < 5; attempt++) {
    let response;
    try {
      response = await fetch("https://integrate.api.nvidia.com/v1/chat/completions", {
        method: "POST",
        headers: {
          "authorization": `Bearer ${env.NVIDIA_API_KEY}`,
          "content-type": "application/json",
          "accept": "application/json",
        },
        body: JSON.stringify({ model, messages, temperature, max_tokens: maxTokens, stream: false }),
      });
      lastStatus = response.status;
      lastText = await response.text();
      if (response.ok) {
        const data = JSON.parse(lastText);
        return data.choices?.[0]?.message?.content?.trim() || "";
      }
      if (![408, 429, 500, 502, 503, 504].includes(response.status)) break;
      const retryAfter = Number(response.headers.get("retry-after"));
      const backoff = Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : 700 * (2 ** attempt) + Math.floor(Math.random() * 350);
      await sleep(Math.min(backoff, 9000));
    } catch (error) {
      lastText = String(error?.message || error);
      if (attempt === 4) break;
      await sleep(700 * (2 ** attempt));
    }
  }
  throw new Error(`NVIDIA ${lastStatus || "network"}: ${lastText.slice(0, 300)}`);
}

async function nvidiaStream(env, messages, requestedModel, requestedEffort) {
  const model = ALLOWED_MODELS.has(requestedModel) ? requestedModel : (env.NVIDIA_MODEL || "nvidia/nemotron-3-super-120b-a12b");
  const allowed = model.includes("ultra") ? new Set(["none", "medium", "high"]) : new Set(["none", "low", "high"]);
  const reasoningEffort = allowed.has(requestedEffort) ? requestedEffort : (model.includes("ultra") ? "medium" : "low");
  let lastText = "";
  for (let attempt = 0; attempt < 5; attempt++) {
    const response = await fetch("https://integrate.api.nvidia.com/v1/chat/completions", {
      method: "POST",
      headers: { "authorization": `Bearer ${env.NVIDIA_API_KEY}`, "content-type": "application/json", "accept": "text/event-stream" },
      body: JSON.stringify({ model, messages, temperature: 0.45, max_tokens: 4096, stream: true, reasoning_effort: reasoningEffort }),
    });
    if (response.ok) return response;
    lastText = await response.text();
    if (![408,429,500,502,503,504].includes(response.status)) throw new Error(`NVIDIA ${response.status}: ${lastText.slice(0,300)}`);
    const wait = Number(response.headers.get("retry-after"));
    await sleep(Math.min(wait > 0 ? wait * 1000 : 700 * (2 ** attempt) + Math.random() * 350, 9000));
  }
  throw new Error(`NVIDIA overloaded: ${lastText.slice(0,300)}`);
}

async function github(env, path, init = {}) {
  const response = await fetch(`https://api.github.com/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}${path}`, {
    ...init,
    headers: {
      "authorization": `Bearer ${env.WORKAI_GITHUB_TOKEN}`,
      "accept": "application/vnd.github+json",
      "x-github-api-version": "2022-11-28",
      "user-agent": "WorkAI-Agent",
      ...(init.headers || {}),
    },
  });
  return response;
}

function parseJsonObject(text) {
  const start = text.indexOf("{");
  const end = text.lastIndexOf("}");
  if (start < 0 || end <= start) return null;
  try { return JSON.parse(text.slice(start, end + 1)); } catch { return null; }
}

async function createPlan(env, prompt, kind) {
  const raw = await nvidia(env, [
    { role: "system", content: "Ты планировщик WorkAI. Верни только JSON без markdown: {\"tasks\":[\"...\"]}. Нужно 3-7 коротких реальных этапов. Последние этапы обязательно: сборка, проверка, публикация artifact." },
    { role: "user", content: `Тип результата: ${kind}. Задача: ${prompt}` },
  ], 700, 0.2);
  const parsed = parseJsonObject(raw);
  const tasks = Array.isArray(parsed?.tasks) ? parsed.tasks.map(String).filter(Boolean).slice(0, 7) : [];
  return tasks.length ? tasks : ["Анализ запроса", "Создание файлов", "Сборка", "Проверка", "Публикация artifact"];
}

async function startJob(request, env, url) {
  const body = await request.json();
  const prompt = String(body.prompt || "").trim();
  const kind = String(body.kind || "apk").toLowerCase();
  if (!prompt) return json({ error: "prompt_required" }, 400, cors(request));
  if (!["apk", "mtz", "zip"].includes(kind)) return json({ error: "unsupported_kind" }, 400, cors(request));
  const id = crypto.randomUUID();
  const tasks = await createPlan(env, prompt, kind);
  const attachments = [];
  for (const item of (Array.isArray(body.attachments) ? body.attachments : []).slice(0, 8)) {
    const name = String(item?.name || "file.bin").replace(/[^\p{L}\p{N}._ -]/gu, "_").slice(0, 120);
    const mime = String(item?.mime || "application/octet-stream").slice(0, 120);
    const base64 = String(item?.base64 || "");
    if (!base64 || base64.length > 36_000_000) throw new Error(`invalid_attachment:${name}`);
    const created = await github(env, "/git/blobs", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ content: base64, encoding: "base64" }),
    });
    if (!created.ok) throw new Error(`attachment_upload_failed:${name}:${created.status}`);
    attachments.push({ name, mime, sha: (await created.json()).sha });
  }
  const dispatch = await github(env, "/dispatches", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      event_type: "workai_build",
      client_payload: { id, prompt, kind, tasks, attachments, callback_origin: url.origin },
    }),
  });
  if (!dispatch.ok) return json({ error: "dispatch_failed", detail: (await dispatch.text()).slice(0, 500) }, 502, cors(request));
  return json({ id, kind, tasks, status: "queued" }, 202, cors(request));
}

async function findRun(env, id) {
  const response = await github(env, "/actions/workflows/agent-build.yml/runs?event=repository_dispatch&per_page=50");
  if (!response.ok) throw new Error(`GitHub runs ${response.status}`);
  const data = await response.json();
  return (data.workflow_runs || []).find(r => String(r.display_title || "").includes(id));
}

async function jobStatus(request, env, id) {
  const run = await findRun(env, id);
  if (!run) return json({ id, status: "queued", steps: [] }, 200, cors(request));
  const jobsResponse = await github(env, `/actions/runs/${run.id}/jobs`);
  const jobsData = jobsResponse.ok ? await jobsResponse.json() : { jobs: [] };
  const steps = (jobsData.jobs || []).flatMap(job => (job.steps || []).map(step => ({
    title: step.name,
    status: step.status,
    conclusion: step.conclusion,
  })));
  let artifact = null;
  if (run.conclusion === "success") {
    const release = await github(env, `/releases/tags/job-${id}`);
    if (release.ok) {
      const releaseData = await release.json();
      const asset = (releaseData.assets || [])[0];
      if (asset) artifact = { name: asset.name, size: asset.size, download: `/v1/jobs/${id}/download` };
    }
  }
  return json({ id, status: run.status, conclusion: run.conclusion, runUrl: run.html_url, steps, artifact }, 200, cors(request));
}

async function downloadResult(request, env, id) {
  const release = await github(env, `/releases/tags/job-${id}`);
  if (!release.ok) return json({ error: "artifact_not_ready" }, 404, cors(request));
  const releaseData = await release.json();
  const asset = (releaseData.assets || [])[0];
  if (!asset) return json({ error: "artifact_not_ready" }, 404, cors(request));
  const binary = await fetch(asset.url, {
    headers: {
      "authorization": `Bearer ${env.WORKAI_GITHUB_TOKEN}`,
      "accept": "application/octet-stream",
      "user-agent": "WorkAI-Agent",
    },
    redirect: "follow",
  });
  if (!binary.ok) return json({ error: "download_failed" }, 502, cors(request));
  return new Response(binary.body, {
    status: 200,
    headers: {
      "content-type": asset.content_type || "application/octet-stream",
      "content-length": String(asset.size),
      "content-disposition": `attachment; filename=\"${asset.name.replaceAll('"', '')}\"`,
    },
  });
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors(request) });
    if (url.pathname === "/health") return json({ ok: true, service: "WorkAI", model: env.NVIDIA_MODEL }, 200, cors(request));
    if (!authorized(request, env)) return json({ error: "unauthorized" }, 401, cors(request));
    try {
      if (url.pathname === "/v1/chat" && request.method === "POST") {
        const body = await request.json();
        const messages = Array.isArray(body.messages) ? body.messages.slice(-30) : [];
        const model = ALLOWED_MODELS.has(body.model) ? body.model : env.NVIDIA_MODEL;
        const answer = await nvidia(env, [{ role: "system", content: `Ты WorkAI, точный русскоязычный ассистент. Текущая модель: ${model}. Если тебя спрашивают о модели, назови именно её.` }, ...messages], 2048, 0.45, model);
        return json({ answer, model }, 200, cors(request));
      }
      if (url.pathname === "/v1/chat/stream" && request.method === "POST") {
        const body = await request.json();
        const model = ALLOWED_MODELS.has(body.model) ? body.model : env.NVIDIA_MODEL;
        const messages = Array.isArray(body.messages) ? body.messages.slice(-30) : [];
        const system = { role: "system", content: `Ты WorkAI — мобильный AI-агент. Используй Markdown: **жирный текст**, списки и тройные backticks для кода. Если приложен разбор MTZ, анализируй структуру темы, manifest, XML и ресурсы как специалист по HyperOS/MIUI. Доступные навыки: Android/Jetpack Compose, Gradle, APK build/debug, MTZ/ZIP-анализ, XML/JSON, редактирование архивов, проверка результата. Не раскрывай внутренний chain-of-thought; давай только вывод и краткие понятные этапы. Текущая модель: ${model}.` };
        const upstream = await nvidiaStream(env, [system, ...messages], model, String(body.reasoning_effort || ""));
        return new Response(upstream.body, { status: 200, headers: { ...cors(request), "content-type": "text/event-stream; charset=utf-8", "cache-control": "no-cache", "x-accel-buffering": "no" } });
      }
      if (url.pathname === "/v1/jobs" && request.method === "POST") return await startJob(request, env, url);
      const match = url.pathname.match(/^\/v1\/jobs\/([0-9a-f-]+)(\/download)?$/);
      if (match && request.method === "GET") return match[2] ? await downloadResult(request, env, match[1]) : await jobStatus(request, env, match[1]);
      return json({ error: "not_found" }, 404, cors(request));
    } catch (error) {
      return json({ error: "internal_error", detail: String(error?.message || error).slice(0, 700) }, 500, cors(request));
    }
  },
};
