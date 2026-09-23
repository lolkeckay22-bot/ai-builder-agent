// Worker tests for the current main-line backend (live event-driven agent).
// Run: node --test worker/tests/zen.test.js
// Only the provider HTTP boundary is stubbed; all routing/tool logic is real.
import { describe, it, afterEach } from "node:test";
import assert from "node:assert/strict";

import { zenRoute, parseZenFailure, ZenError, zenModels } from "../src/zen.js";
import worker, { CHAT_TOOLS, executeChatTool, webSearch, webFetchPage, attachImages } from "../src/index.js";
import { SKILL_BUNDLES } from "../src/skills.bundle.js";

const realFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = realFetch; });

const TOKEN = "test-device-token-0123456789";
const ENV = { WORKAI_DEVICE_TOKEN: TOKEN };
const authed = (path, method = "GET") => new Request(`https://worker.test${path}`, { method, headers: { "x-workai-token": TOKEN } });

describe("zen routing", () => {
  it("routes the four required models to documented endpoints", () => {
    assert.equal(zenRoute("nemotron-3-ultra-free").endpoint, "https://opencode.ai/zen/v1/chat/completions");
    assert.equal(zenRoute("mimo-v2.6-flash-free").endpoint, "https://opencode.ai/zen/v1/chat/completions");
    assert.equal(zenRoute("muse-spark-1.3-contributor-free").endpoint, "https://opencode.ai/zen/v1/responses");
    assert.equal(zenRoute("muse-spark-1.2-contributor-free").endpoint, "https://opencode.ai/zen/v1/responses");
    assert.ok(zenModels().length >= 4);
  });
  it("classifies live failure shapes", () => {
    const free = parseZenFailure({ status: 403, bodyText: `{"type":"error","error":{"type":"FreeTierError","message":"free tier can only be used from within OpenCode"}}`, endpoint: "e", model: "mimo-v2.6-flash-free" });
    assert.ok(free instanceof ZenError && free.zenType === "FreeTierError");
    const auth = parseZenFailure({ status: 401, bodyText: `{"type":"error","error":{"type":"AuthError","message":"Invalid API key."}}`, endpoint: "e", model: "m" });
    assert.equal(auth.zenType, "AuthError");
    const cf = parseZenFailure({ status: 403, bodyText: "<html>error code: 1010</html>", endpoint: "e", model: "m" });
    assert.equal(cf.zenType, "CloudflareBlock");
  });
});

describe("chat tools", () => {
  it("has no get_weather; has universal web_search + web_fetch", () => {
    const names = CHAT_TOOLS.map(t => t.function.name);
    assert.ok(!names.includes("get_weather"), "get_weather must stay removed");
    assert.ok(names.includes("web_search") && names.includes("web_fetch") && names.includes("get_exchange_rate"));
  });
  it("web_fetch reads a real page", async () => {
    const sent = [];
    const out = await executeChatTool({}, { id: "t1", function: { name: "web_fetch", arguments: JSON.stringify({ url: "https://example.com" }) } }, e => sent.push(e));
    const page = JSON.parse(out.result);
    assert.equal(page.ok, true);
    assert.match(page.text, /Example Domain/);
    assert.equal(out.sources.length, 1);
    assert.ok(sent.some(e => e.type === "tool_call") && sent.some(e => e.type === "tool_result"));
  });
  it("web_search without BRAVE_API_KEY uses the real fallback chain", async () => {
    const found = await webSearch({}, "Android 17 release date");
    assert.ok(Array.isArray(found.results));
    assert.equal(found.engine, "duckduckgo");
  });
  it("web_fetch rejects non-https/private targets", async () => {
    const out = await executeChatTool({}, { id: "t2", function: { name: "web_fetch", arguments: JSON.stringify({ url: "http://169.254.169.254/" }) } }, () => {});
    assert.equal(JSON.parse(out.result).ok, false);
  });
});

describe("vision passthrough", () => {
  it("attaches images as multimodal parts, not OCR text", () => {
    const { messages, count } = attachImages(
      [{ role: "user", content: "Опиши изображение" }],
      [{ mime: "image/jpeg", base64: "aGVsbG8=" }],
    );
    assert.equal(count, 1);
    const parts = messages[0].content;
    assert.ok(Array.isArray(parts));
    assert.ok(parts.some(p => p.type === "image_url"));
  });
});

describe("skills bundle", () => {
  it("embeds all checked-in skills", () => {
    assert.ok(SKILL_BUNDLES.length >= 8);
    assert.ok(SKILL_BUNDLES.some(s => s.name === "web-research" && s.text.length > 100));
  });
});

describe("http routes", () => {
  it("unauthorized without token", async () => {
    assert.equal((await worker.fetch(new Request("https://worker.test/v1/zen/models"), ENV)).status, 401);
  });
  it("/v1/zen/models lists the catalogue", async () => {
    const res = await worker.fetch(authed("/v1/zen/models"), ENV);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.ok(body.models.some(m => m.id === "muse-spark-1.3-contributor-free" && m.endpoint.endsWith("/responses")));
  });
  it("/v1/zen/diagnose static checks without key", async () => {
    globalThis.fetch = async url => (String(url).endsWith("/models")
      ? new Response(JSON.stringify({ object: "list", data: [] }), { status: 200 })
      : new Response("{}", { status: 404 }));
    const res = await worker.fetch(authed("/v1/zen/diagnose"), ENV);
    const body = await res.json();
    assert.equal(body.provider, "zen");
    assert.equal(body.checks.keyPresent, false);
    assert.equal(body.checks.models.length, 4);
  });
  it("zen chat without OPENCODE_API_KEY is 503 provider_error, NVIDIA untouched", async () => {
    const calls = [];
    globalThis.fetch = async (url, init) => { calls.push(String(url)); return new Response("{}", { status: 200 }); };
    const res = await worker.fetch(new Request("https://worker.test/v1/chat", {
      method: "POST", headers: { "content-type": "application/json", "x-workai-token": TOKEN },
      body: JSON.stringify({ model: "mimo-v2.6-flash-free", messages: [{ role: "user", content: "hi" }] }),
    }), ENV);
    assert.equal(res.status, 503);
    const body = await res.json();
    assert.equal(body.error, "provider_error");
    assert.equal(body.provider, "zen");
    assert.ok(calls.every(u => !u.includes("nvidia.com")));
  });
  it("invalid JSON is 400, not 500", async () => {
    const res = await worker.fetch(new Request("https://worker.test/v1/chat", {
      method: "POST", headers: { "content-type": "application/json", "x-workai-token": TOKEN }, body: "{bad",
    }), ENV);
    assert.equal(res.status, 400);
  });
});
