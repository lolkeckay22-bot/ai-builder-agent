// OpenCode Zen provider.
//
// Docs: https://opencode.ai/docs/zen
// Model catalogue: https://opencode.ai/zen/v1/models
//
// Critical routing fact (verified 2026-09-23 against live docs + API):
// Zen exposes a DIFFERENT HTTP endpoint per model family:
//   - chat/completions  (OpenAI-compatible): nemotron-3-ultra-free,
//     mimo-v2.6-flash-free, deepseek/minimax/glm/kimi families, big-pickle
//   - responses         (OpenAI Responses API): muse-spark-*-contributor-free,
//     muse-spark-1.2/1.3, gpt-*, grok-*
//   - messages          (Anthropic-compatible): claude-*, qwen3.*
//   - models/<id>       (provider-native): gemini-*
//   - systemone         : jev-*
//
// Using the wrong endpoint for a model is a hard API error. This module routes
// each known model to its documented endpoint and NEVER silently falls back to
// NVIDIA/another provider: a Zen failure is surfaced to the caller as a
// structured zen_error (http status, zen error type, endpoint used).

export const ZEN_BASE = "https://opencode.ai/zen/v1";
export const ZEN_USER_AGENT = "WorkAI-Agent/1.0 (+https://github.com/lolkeckay22-bot/ai-builder-agent)";

const CHAT_COMPLETIONS = `${ZEN_BASE}/chat/completions`;
const RESPONSES = `${ZEN_BASE}/responses`;
const MESSAGES = `${ZEN_BASE}/messages`;
const SYSTEMONE = `${ZEN_BASE}/systemone`;

// Models explicitly required by the project spec, with verified endpoints.
const KNOWN_MODELS = {
  "nemotron-3-ultra-free": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: false },
  "muse-spark-1.3-contributor-free": { endpoint: RESPONSES, protocol: "openai-responses", vision: true },
  "mimo-v2.6-flash-free": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: true },
  "muse-spark-1.2-contributor-free": { endpoint: RESPONSES, protocol: "openai-responses", vision: true },
  // Paid siblings (same families, same protocols).
  "nemotron-3.5-lightning-free": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: false },
  "mimo-v2.5-free": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: true },
  "ling-3.0-flash-fin-free": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: false },
  "big-pickle": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: false },
  "muse-spark-1.3": { endpoint: RESPONSES, protocol: "openai-responses", vision: true },
  "muse-spark-1.2": { endpoint: RESPONSES, protocol: "openai-responses", vision: true },
  "deepseek-v4-pro": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: false },
  "deepseek-v4-flash": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: false },
  "deepseek-v4-flash-vision-exp": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: true },
  "minimax-m3": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: false },
  "glm-5.3-flash": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: false },
  "kimi-k2.6": { endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: true },
};

export function zenRoute(model) {
  const id = String(model || "");
  if (KNOWN_MODELS[id]) return { model: id, ...KNOWN_MODELS[id], known: true };
  if (/^(gpt-|grok-|muse-spark)/.test(id)) return { model: id, endpoint: RESPONSES, protocol: "openai-responses", vision: false, known: false };
  if (/^(claude-|qwen3)/.test(id)) return { model: id, endpoint: MESSAGES, protocol: "anthropic-messages", vision: false, known: false };
  if (/^gemini-/.test(id)) return { model: id, endpoint: `${ZEN_BASE}/models/${id}`, protocol: "provider-native", vision: true, known: false };
  if (/^jev-/.test(id)) return { model: id, endpoint: SYSTEMONE, protocol: "systemone", vision: false, known: false };
  // Default: OpenAI-compatible chat endpoint (documented for the -free coder lineup).
  return { model: id, endpoint: CHAT_COMPLETIONS, protocol: "openai-chat", vision: false, known: false };
}

export function zenModels() {
  return Object.entries(KNOWN_MODELS).map(([id, meta]) => ({ id, ...meta }));
}

export class ZenError extends Error {
  constructor({ httpStatus, zenType, message, endpoint, model, cloudflare = false, retryable = false }) {
    super(message);
    this.name = "ZenError";
    this.provider = "zen";
    this.httpStatus = httpStatus;
    this.zenType = zenType;
    this.endpoint = endpoint;
    this.model = model;
    this.cloudflare = cloudflare;
    this.retryable = retryable;
  }
  toJSON() {
    return {
      error: "zen_error",
      provider: "zen",
      httpStatus: this.httpStatus,
      zenType: this.zenType,
      message: this.message,
      endpoint: this.endpoint,
      model: this.model,
      cloudflare: this.cloudflare,
      retryable: this.retryable,
    };
  }
}

function zenHeaders(apiKey) {
  return {
    authorization: `Bearer ${apiKey}`,
    "content-type": "application/json",
    accept: "application/json",
    "user-agent": ZEN_USER_AGENT,
  };
}

function classifyStatus(status) {
  return [408, 429, 500, 502, 503, 504].includes(status);
}

// Parse a Zen error body into a ZenError. Verified live shapes (2026-09-23):
//   401 {"type":"error","error":{"type":"AuthError","message":"Invalid API key."}}
//   401 {"type":"error","error":{"type":"ModelError","message":"Model X is not supported"}}
//   403 {"type":"error","error":{"type":"FreeTierError","message":"...free tier can only be used from within OpenCode"}}
// Cloudflare bot block: HTTP 403 with text/html body mentioning "error code: 1010".
export function parseZenFailure({ status, bodyText, endpoint, model }) {
  const snippet = String(bodyText || "").slice(0, 500);
  const lower = snippet.toLowerCase();
  if (status === 403 && (lower.includes("cloudflare") || lower.includes("error code: 1010") || lower.includes("<html"))) {
    return new ZenError({
      httpStatus: 403, zenType: "CloudflareBlock", endpoint, model, cloudflare: true, retryable: false,
      message: `Zen request blocked by Cloudflare (1010) at ${endpoint}. The Worker egress IP is challenged; this must be allowlisted or requests must originate from an allowed network. Body: ${snippet.slice(0, 200)}`,
    });
  }
  let zenType = `HTTP_${status}`;
  let message = snippet || `Zen HTTP ${status}`;
  try {
    const parsed = JSON.parse(snippet);
    const inner = parsed?.error && typeof parsed.error === "object" ? parsed.error : parsed;
    if (inner?.type) zenType = String(inner.type);
    if (inner?.message) message = String(inner.message);
  } catch { /* keep raw snippet */ }
  if (zenType === "FreeTierError") {
    message = `${message} (model=${model}; free-tier Zen models are gated to usage from within OpenCode — a valid OPENCODE_API_KEY with billing/enabled model access is required)`;
  }
  return new ZenError({
    httpStatus: status, zenType, message, endpoint, model,
    retryable: classifyStatus(status),
  });
}

export function zenKeyStatus(env) {
  const key = env?.OPENCODE_API_KEY || "";
  if (!key) return { present: false, hint: "Set OPENCODE_API_KEY as a Worker secret (wrangler secret put OPENCODE_API_KEY). Without it every Zen call fails." };
  if (key.length < 20) return { present: true, validShape: false, hint: "OPENCODE_API_KEY looks truncated (length < 20)." };
  return { present: true, validShape: true, prefix: key.slice(0, 3) + "…", length: key.length };
}

// --- OpenAI-chat protocol -------------------------------------------------

function toChatMessages(messages) {
  return (messages || []).map(m => {
    if (m.role === "tool") return { role: "tool", tool_call_id: m.tool_call_id, content: String(m.content ?? "") };
    const content = Array.isArray(m.content) ? m.content : String(m.content ?? "");
    const out = { role: m.role, content };
    if (m.tool_calls) out.tool_calls = m.tool_calls;
    if (m.name) out.name = m.name;
    return out;
  });
}

export async function zenChatNonStreaming(env, { model, messages, tools, maxTokens = 2048, temperature = 0.4 }) {
  const route = zenRoute(model);
  if (route.protocol !== "openai-chat") {
    throw new ZenError({
      httpStatus: 0, zenType: "WrongEndpoint", endpoint: route.endpoint, model,
      message: `Model ${model} uses the ${route.protocol} protocol at ${route.endpoint}, not chat/completions. Use the matching caller.`,
    });
  }
  const key = env?.OPENCODE_API_KEY || "";
  if (!key) throw new ZenError({ httpStatus: 0, zenType: "MissingApiKey", endpoint: route.endpoint, model, message: "OPENCODE_API_KEY is not configured in the Worker runtime." });
  const body = { model, messages: toChatMessages(messages), temperature, max_tokens: maxTokens, stream: false };
  if (tools?.length) body.tools = tools;
  const res = await fetch(route.endpoint, { method: "POST", headers: zenHeaders(key), body: JSON.stringify(body) });
  const text = await res.text();
  if (!res.ok) throw parseZenFailure({ status: res.status, bodyText: text, endpoint: route.endpoint, model });
  let data;
  try { data = JSON.parse(text); } catch { throw new ZenError({ httpStatus: 200, zenType: "BadUpstreamBody", endpoint: route.endpoint, model, message: "Zen returned non-JSON on HTTP 200." }); }
  return data;
}

// Streaming variant returns the upstream Response (SSE) for piping, after
// surfacing errors eagerly (reads status before returning body).
export async function zenChatStreamUpstream(env, { model, messages, tools, maxTokens = 4096, temperature = 0.4 }) {
  const route = zenRoute(model);
  if (route.protocol !== "openai-chat") {
    throw new ZenError({ httpStatus: 0, zenType: "WrongEndpoint", endpoint: route.endpoint, model, message: `Model ${model} uses ${route.protocol}; streaming caller mismatch.` });
  }
  const key = env?.OPENCODE_API_KEY || "";
  if (!key) throw new ZenError({ httpStatus: 0, zenType: "MissingApiKey", endpoint: route.endpoint, model, message: "OPENCODE_API_KEY is not configured in the Worker runtime." });
  const body = { model, messages: toChatMessages(messages), temperature, max_tokens: maxTokens, stream: true, stream_options: { include_usage: true } };
  if (tools?.length) body.tools = tools;
  const res = await fetch(route.endpoint, {
    method: "POST",
    headers: { ...zenHeaders(key), accept: "text/event-stream" },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw parseZenFailure({ status: res.status, bodyText: await res.text(), endpoint: route.endpoint, model });
  return res;
}

// --- OpenAI Responses protocol -------------------------------------------
// Used by muse-spark-*-contributor-free and the gpt/grok families.

function toResponsesInput(messages) {
  return (messages || []).map(m => {
    if (m.role === "tool") return { type: "function_call_output", call_id: m.tool_call_id, output: String(m.content ?? "") };
    if (Array.isArray(m.content)) {
      const parts = m.content.map(p => {
        if (p.type === "image_url" || p.type === "input_image") {
          const url = typeof p.image_url === "string" ? p.image_url : p.image_url?.url;
          return { type: "input_image", image_url: url };
        }
        return { type: "input_text", text: String(p.text ?? p.content ?? "") };
      });
      return { role: m.role === "assistant" ? "assistant" : "user", content: parts };
    }
    return { role: m.role === "assistant" ? "assistant" : "user", content: String(m.content ?? "") };
  });
}

function toResponsesTools(tools) {
  return (tools || []).map(t => {
    const fn = t.function || t;
    return { type: "function", name: fn.name, description: fn.description || "", parameters: fn.parameters || { type: "object", properties: {} } };
  });
}

// Non-streaming Responses call, normalized to an OpenAI-chat-like shape:
// { choices: [{ message: { content, tool_calls } }], usage? }
export async function zenResponsesNonStreaming(env, { model, messages, tools, maxTokens = 2048, temperature = 0.4 }) {
  const route = zenRoute(model);
  if (route.protocol !== "openai-responses") {
    throw new ZenError({ httpStatus: 0, zenType: "WrongEndpoint", endpoint: route.endpoint, model, message: `Model ${model} uses ${route.protocol}, not responses.` });
  }
  const key = env?.OPENCODE_API_KEY || "";
  if (!key) throw new ZenError({ httpStatus: 0, zenType: "MissingApiKey", endpoint: route.endpoint, model, message: "OPENCODE_API_KEY is not configured in the Worker runtime." });
  const body = { model, input: toResponsesInput(messages), temperature, max_output_tokens: maxTokens, stream: false };
  if (tools?.length) body.tools = toResponsesTools(tools);
  const res = await fetch(route.endpoint, { method: "POST", headers: zenHeaders(key), body: JSON.stringify(body) });
  const text = await res.text();
  if (!res.ok) throw parseZenFailure({ status: res.status, bodyText: text, endpoint: route.endpoint, model });
  let data;
  try { data = JSON.parse(text); } catch { throw new ZenError({ httpStatus: 200, zenType: "BadUpstreamBody", endpoint: route.endpoint, model, message: "Zen returned non-JSON on HTTP 200." }); }
  return normalizeResponsesObject(data);
}

export function normalizeResponsesObject(data) {
  const textChunks = [];
  const toolCalls = [];
  const output = data?.output || data?.response?.output || [];
  for (const item of output) {
    if (!item || typeof item !== "object") continue;
    if (item.type === "message" && Array.isArray(item.content)) {
      for (const c of item.content) {
        if (c.type === "output_text" && c.text) textChunks.push(typeof c.text === "string" ? c.text : (c.text.value || ""));
      }
    }
    if (item.type === "function_call") {
      toolCalls.push({
        id: item.call_id || item.id,
        type: "function",
        function: { name: item.name, arguments: typeof item.arguments === "string" ? item.arguments : JSON.stringify(item.arguments ?? {}) },
      });
    }
  }
  const flat = typeof data?.output_text === "string" ? data.output_text : textChunks.join("");
  return { choices: [{ message: { content: flat, tool_calls: toolCalls.length ? toolCalls : undefined } }], usage: data?.usage };
}

// Runtime diagnosis for the Zen integration. With probe=false only static
// checks run (safe, no spend). With probe=true a minimal live call
// (max_tokens=1) is issued per required model and the REAL provider answer or
// error is reported — never replaced by another provider.
export async function zenDiagnose(env, { probe = false } = {}) {
  const required = [
    "nemotron-3-ultra-free",
    "muse-spark-1.3-contributor-free",
    "mimo-v2.6-flash-free",
    "muse-spark-1.2-contributor-free",
  ];
  const key = zenKeyStatus(env);
  const checks = {
    keyPresent: key.present === true,
    keyShape: key.validShape === true,
    keyDetails: key.present ? { length: key.length, prefix: key.prefix, validShape: key.validShape } : { hint: key.hint },
    baseReachable: false,
    models: [],
  };
  try {
    const r = await fetch(`${ZEN_BASE}/models`, { headers: { "user-agent": ZEN_USER_AGENT, accept: "application/json" } });
    checks.baseReachable = r.ok;
    checks.modelsEndpoint = r.status;
  } catch (e) {
    checks.baseReachable = false;
    checks.baseError = String(e?.message || e).slice(0, 200);
  }
  for (const id of required) {
    const route = zenRoute(id);
    const entry = { model: id, endpoint: route.endpoint, protocol: route.protocol, known: route.known, live: null };
    if (probe && key.present) {
      try {
        if (route.protocol === "openai-responses") {
          const out = await zenResponsesNonStreaming(env, { model: id, messages: [{ role: "user", content: "Reply with the single word: ok" }], maxTokens: 8 });
          entry.live = { ok: true, sample: String(out.choices?.[0]?.message?.content || "").slice(0, 120) };
        } else {
          const out = await zenChatNonStreaming(env, { model: id, messages: [{ role: "user", content: "Reply with the single word: ok" }], maxTokens: 8 });
          entry.live = { ok: true, sample: String(out.choices?.[0]?.message?.content || "").slice(0, 120) };
        }
      } catch (e) {
        entry.live = e instanceof ZenError
          ? { ok: false, ...e.toJSON() }
          : { ok: false, error: "unknown", message: String(e?.message || e).slice(0, 300) };
      }
    }
    checks.models.push(entry);
  }
  return {
    ok: checks.keyPresent && checks.baseReachable && (!probe || checks.models.every(m => m.live?.ok)),
    provider: "zen",
    base: ZEN_BASE,
    checks,
    notes: [
      "Free-tier (-free) models are gated by OpenCode to in-OpenCode usage; outside it the API returns 403 FreeTierError. That is an external restriction, reported verbatim.",
      "muse-spark-*-contributor-free MUST use /v1/responses (Responses API). Calling chat/completions for them is a WrongEndpoint error, not a provider outage.",
      "No silent fallback: on any Zen failure the Worker returns zen_error; NVIDIA is only used for explicit nvidia/* models.",
    ],
  };
}
