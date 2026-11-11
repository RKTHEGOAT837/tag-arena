// AI DJ backend.
//
// Keeps secrets (Anthropic API key, Jamendo client id) off the phone. The Android app authenticates with a
// per-device token and sends Messages API turns (system prompt, conversation, DJ tool definitions); this
// server validates and bounds them, calls Claude with the official SDK, and returns the raw response so the
// app can run its local tool loop. It also proxies Jamendo catalogue searches.
//
// Two interchangeable LLM providers (the phone app doesn't know or care which one runs):
//   - claude: Anthropic Messages API via the official SDK
//   - gemini: Google Gemini (generativelanguage.googleapis.com) – requests/responses are translated
//     to/from the Messages API shape (tool_use/tool_result <-> functionCall/functionResponse), so the
//     device-side tool loop is identical. Gemini has a free tier, which makes it the cheap option.
//
// Environment:
//   LLM_PROVIDER        optional – "claude" or "gemini" (default: claude if ANTHROPIC_API_KEY is set, else gemini)
//   ANTHROPIC_API_KEY   required for claude
//   GEMINI_API_KEY      required for gemini (free at https://aistudio.google.com/apikey)
//   CLAUDE_MODEL        optional – default "claude-opus-5-5"
//   GEMINI_MODEL        optional – default "gemini-2.5-flash" (use "gemini-2.5-flash-lite" for the largest free quota)
//   APP_TOKENS          required – comma-separated device tokens accepted from the app
//   JAMENDO_CLIENT_ID   optional – enables /v1/jamendo/tracks
//   PORT                optional – default 8787
//   RATE_LIMIT_PER_MIN  optional – LLM requests per token per minute (default 20)

import Anthropic from "@anthropic-ai/sdk";
import crypto from "node:crypto";
import http from "node:http";

const MAX_BODY = 512 * 1024;
const ALLOWED_PURPOSES = new Set(["decision", "command"]);
const JAMENDO_PARAMS = new Set(["format", "limit", "search", "include", "audiodlformat", "order", "tags", "offset"]);

// ---------------------------------------------------------------------------- Gemini translation

/** Messages-API JSON schema -> Gemini Schema (keep the supported subset, uppercase types). */
export function schemaToGemini(s) {
  if (!s || typeof s !== "object") return undefined;
  const out = {};
  if (s.type) out.type = String(s.type).toUpperCase();
  if (s.description) out.description = s.description;
  if (s.enum) out.enum = s.enum;
  if (s.minimum !== undefined) out.minimum = s.minimum;
  if (s.maximum !== undefined) out.maximum = s.maximum;
  if (s.items) out.items = schemaToGemini(s.items);
  if (s.properties) { out.properties = {}; for (const [k, v] of Object.entries(s.properties)) out.properties[k] = schemaToGemini(v); }
  if (Array.isArray(s.required) && s.required.length) out.required = s.required;
  return out;
}

/** Messages-API turn -> Gemini generateContent request body. */
export function toGeminiRequest(body, { maxOutputTokens }) {
  const idToName = {};
  for (const m of body.messages) if (Array.isArray(m.content)) {
    for (const b of m.content) if (b.type === "tool_use") idToName[b.id] = b.name;
  }
  const contents = [];
  for (const m of body.messages) {
    const role = m.role === "assistant" ? "model" : "user";
    const parts = [];
    if (typeof m.content === "string") { if (m.content) parts.push({ text: m.content }); }
    else for (const b of m.content) {
      if (b.type === "text" && b.text) parts.push({ text: b.text });
      else if (b.type === "tool_use") parts.push({ functionCall: { name: b.name, args: b.input ?? {} } });
      else if (b.type === "tool_result") {
        let data;
        const raw = typeof b.content === "string" ? b.content : JSON.stringify(b.content ?? "");
        try { data = JSON.parse(raw); } catch { data = { text: raw }; }
        if (data === null || typeof data !== "object" || Array.isArray(data)) data = { result: data };
        parts.push({ functionResponse: { name: idToName[b.tool_use_id] ?? "unknown_tool", response: data } });
      }
      // thinking / other block types carry no information Gemini can use: skipped.
    }
    if (parts.length) contents.push({ role, parts });
  }
  return {
    systemInstruction: { parts: [{ text: body.system }] },
    contents,
    tools: body.tools.length ? [{ functionDeclarations: body.tools.map((t) => ({ name: t.name, description: t.description, parameters: schemaToGemini(t.input_schema) })) }] : undefined,
    generationConfig: { maxOutputTokens, temperature: 0.7 },
  };
}

/** Gemini generateContent response -> Messages-API response shape (what the app's tool loop consumes). */
export function fromGeminiResponse(json, model) {
  const cand = json.candidates?.[0];
  if (!cand) {
    const blocked = json.promptFeedback?.blockReason;
    return { id: "msg_gemini", type: "message", role: "assistant", model, content: [],
      stop_reason: blocked ? "refusal" : "end_turn", stop_sequence: null,
      usage: { input_tokens: json.usageMetadata?.promptTokenCount ?? 0, output_tokens: 0 } };
  }
  const stamp = Date.now().toString(36);
  const content = [];
  let calls = 0;
  for (const p of cand.content?.parts ?? []) {
    if (p.text) content.push({ type: "text", text: p.text });
    else if (p.functionCall) content.push({ type: "tool_use", id: `toolu_g${stamp}_${calls++}`, name: p.functionCall.name, input: p.functionCall.args ?? {} });
  }
  return {
    id: "msg_gemini", type: "message", role: "assistant", model, content,
    stop_reason: calls > 0 ? "tool_use" : (cand.finishReason === "MAX_TOKENS" ? "max_tokens" : "end_turn"),
    stop_sequence: null,
    usage: { input_tokens: json.usageMetadata?.promptTokenCount ?? 0, output_tokens: json.usageMetadata?.candidatesTokenCount ?? 0 },
  };
}

export function createApp({ client, tokens, provider = "claude", model = "claude-opus-5-5",
  geminiKey = "", geminiModel = "gemini-2.5-flash", geminiBase = "https://generativelanguage.googleapis.com",
  jamendoClientId = "", fetchImpl = fetch, llmFetch = fetch, ratePerMin = 20, log = console }) {
  const tokenBufs = tokens.filter(Boolean).map((t) => Buffer.from(t));
  const buckets = new Map();

  function authorised(req) {
    const h = req.headers["authorization"] || "";
    const m = /^Bearer\s+(.+)$/.exec(h);
    if (!m) return null;
    const given = Buffer.from(m[1].trim());
    for (const t of tokenBufs) {
      if (t.length === given.length && crypto.timingSafeEqual(t, given)) return m[1].trim();
    }
    return null;
  }

  function rateLimited(token) {
    const now = Date.now();
    const b = buckets.get(token) || { tokens: ratePerMin, at: now };
    b.tokens = Math.min(ratePerMin, b.tokens + ((now - b.at) / 60000) * ratePerMin);
    b.at = now;
    if (b.tokens < 1) { buckets.set(token, b); return true; }
    b.tokens -= 1;
    buckets.set(token, b);
    return false;
  }

  function send(res, status, body) {
    const s = JSON.stringify(body);
    res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(s), "cache-control": "no-store" });
    res.end(s);
  }

  const err = (res, status, message) => send(res, status, { type: "error", error: { type: "request_error", message } });

  async function readJson(req) {
    const chunks = [];
    let size = 0;
    for await (const c of req) {
      size += c.length;
      if (size > MAX_BODY) throw Object.assign(new Error("Request too large"), { status: 413 });
      chunks.push(c);
    }
    try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
    catch { throw Object.assign(new Error("Invalid JSON"), { status: 400 }); }
  }

  /** Strict shape validation: the app may only send a bounded conversation and well-formed tool definitions. */
  function validateTurn(body) {
    if (typeof body !== "object" || body === null) return "Body must be an object";
    if (!ALLOWED_PURPOSES.has(body.purpose)) return "purpose must be 'decision' or 'command'";
    if (typeof body.system !== "string" || body.system.length > 20000) return "system must be a string (<= 20000 chars)";
    if (!Array.isArray(body.messages) || body.messages.length < 1 || body.messages.length > 40) return "messages must be an array of 1..40";
    for (const m of body.messages) {
      if (!m || (m.role !== "user" && m.role !== "assistant")) return "message roles must be user/assistant";
      if (typeof m.content !== "string" && !Array.isArray(m.content)) return "message content must be a string or array";
    }
    if (body.messages[0].role !== "user") return "first message must be from the user";
    if (!Array.isArray(body.tools) || body.tools.length > 32) return "tools must be an array (<= 32)";
    for (const t of body.tools) {
      if (!t || typeof t.name !== "string" || !/^[a-z_][a-z0-9_]{0,63}$/.test(t.name)) return "invalid tool name";
      if (typeof t.description !== "string" || t.description.length > 2000) return "invalid tool description";
      if (!t.input_schema || t.input_schema.type !== "object") return "tool input_schema must be an object schema";
    }
    return null;
  }

  async function llm(req, res, token) {
    if (rateLimited(token)) return err(res, 429, "Rate limit: too many AI requests from this device");
    const body = await readJson(req);
    const invalid = validateTurn(body);
    if (invalid) return err(res, 400, invalid);
    const decision = body.purpose === "decision";
    if (provider === "gemini") return gemini(res, body, decision);
    try {
      const response = await client.beta.messages.create({
        model,
        max_tokens: decision ? 4000 : 8000,
        system: body.system,
        messages: body.messages,
        tools: body.tools.map((t) => ({ name: t.name, description: t.description, input_schema: t.input_schema })),
        tool_choice: { type: "auto" },
        // Periodic DJ decisions are frequent and simple: low effort keeps them fast and cheap.
        output_config: { effort: decision ? "low" : "medium" },
        // Server-side refusal fallback: if a turn is declined, the API re-runs it on a fallback model.
        betas: ["server-side-fallback-2026-07-01"],
        fallbacks: "default",
      });
      return send(res, 200, response);
    } catch (e) {
      if (e instanceof Anthropic.AuthenticationError) { log.error("Anthropic auth failed – check ANTHROPIC_API_KEY"); return err(res, 502, "Backend is misconfigured (Claude authentication failed)"); }
      if (e instanceof Anthropic.RateLimitError) return err(res, 429, "Claude is rate limited, try again shortly");
      if (e instanceof Anthropic.BadRequestError) return err(res, 400, `Claude rejected the request: ${e.message}`);
      if (e instanceof Anthropic.APIConnectionError) return err(res, 504, "Could not reach Claude");
      if (e instanceof Anthropic.APIError) return err(res, 502, `Claude API error ${e.status ?? ""}`.trim());
      throw e;
    }
  }

  async function gemini(res, body, decision) {
    const url = `${geminiBase}/v1beta/models/${geminiModel}:generateContent`;
    const request = toGeminiRequest(body, { maxOutputTokens: decision ? 2000 : 4000 });
    let r;
    try {
      r = await llmFetch(url, {
        method: "POST",
        headers: { "content-type": "application/json", "x-goog-api-key": geminiKey },
        body: JSON.stringify(request),
      });
    } catch (e) {
      return err(res, 504, `Could not reach Gemini: ${e.message}`);
    }
    const text = await r.text();
    let json;
    try { json = JSON.parse(text); } catch { json = null; }
    if (!r.ok) {
      const message = json?.error?.message ?? text.slice(0, 200);
      if (r.status === 401 || r.status === 403) { log.error("Gemini auth failed – check GEMINI_API_KEY"); return err(res, 502, "Backend is misconfigured (Gemini authentication failed)"); }
      if (r.status === 429) return err(res, 429, "Gemini is rate limited (free-tier quota), try again shortly");
      return err(res, r.status >= 500 ? 502 : 400, `Gemini error: ${message}`);
    }
    return send(res, 200, fromGeminiResponse(json, geminiModel));
  }

  async function jamendo(req, res, url) {
    if (!jamendoClientId) return err(res, 501, "Jamendo is not configured on this backend (JAMENDO_CLIENT_ID)");
    const target = new URL("https://api.jamendo.com/v3.0/tracks/");
    target.searchParams.set("client_id", jamendoClientId);
    for (const [k, v] of url.searchParams) if (JAMENDO_PARAMS.has(k)) target.searchParams.append(k, v.slice(0, 200));
    const r = await fetchImpl(target, { headers: { accept: "application/json" } });
    const text = await r.text();
    res.writeHead(r.status, { "content-type": "application/json", "cache-control": "public, max-age=300" });
    res.end(text);
  }

  return http.createServer(async (req, res) => {
    const url = new URL(req.url, "http://localhost");
    try {
      if (req.method === "GET" && url.pathname === "/health") {
        return send(res, 200, { ok: true, provider, model: provider === "gemini" ? geminiModel : model,
          jamendo: Boolean(jamendoClientId), authorised: Boolean(authorised(req)) });
      }
      const token = authorised(req);
      if (!token) return err(res, 401, "Missing or invalid device token");
      // Kept at the original path so existing app installs keep working, whichever provider runs.
      if (req.method === "POST" && url.pathname === "/v1/claude/messages") return await llm(req, res, token);
      if (req.method === "GET" && url.pathname === "/v1/jamendo/tracks") return await jamendo(req, res, url);
      return err(res, 404, "Not found");
    } catch (e) {
      if (e.status) return err(res, e.status, e.message);
      log.error(e);
      return err(res, 500, "Internal error");
    }
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const tokens = (process.env.APP_TOKENS || "").split(",").map((s) => s.trim()).filter(Boolean);
  const provider = process.env.LLM_PROVIDER || (process.env.ANTHROPIC_API_KEY ? "claude" : "gemini");
  if (!["claude", "gemini"].includes(provider)) { console.error(`Unknown LLM_PROVIDER '${provider}'`); process.exit(1); }
  if (provider === "claude" && !process.env.ANTHROPIC_API_KEY) { console.error("ANTHROPIC_API_KEY is required for LLM_PROVIDER=claude"); process.exit(1); }
  if (provider === "gemini" && !process.env.GEMINI_API_KEY) { console.error("GEMINI_API_KEY is required for LLM_PROVIDER=gemini (free key: https://aistudio.google.com/apikey)"); process.exit(1); }
  if (tokens.length === 0) { console.error("APP_TOKENS is required (comma-separated device tokens)"); process.exit(1); }
  const app = createApp({
    client: provider === "claude" ? new Anthropic() : undefined,
    tokens,
    provider,
    model: process.env.CLAUDE_MODEL || "claude-opus-5-5",
    geminiKey: process.env.GEMINI_API_KEY || "",
    geminiModel: process.env.GEMINI_MODEL || "gemini-2.5-flash",
    jamendoClientId: process.env.JAMENDO_CLIENT_ID || "",
    ratePerMin: Number(process.env.RATE_LIMIT_PER_MIN || 20),
  });
  const port = Number(process.env.PORT || 8787);
  app.listen(port, () => console.log(`AI DJ backend (${provider}) listening on :${port}`));
}
