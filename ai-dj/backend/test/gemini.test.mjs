// Gemini provider tests: no real API calls — a fake fetch captures the exact HTTP request the backend
// sends to generativelanguage.googleapis.com and feeds back canned responses, so the Messages-API <->
// Gemini translation (tools, tool_use/functionCall, tool_result/functionResponse) is verified end to end.
import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { createApp, fromGeminiResponse, schemaToGemini, toGeminiRequest } from "../server.mjs";

let server, base;
const captured = [];
let nextResponse;

const fakeGeminiFetch = async (url, init) => {
  captured.push({ url: String(url), headers: init.headers, body: JSON.parse(init.body) });
  return new Response(JSON.stringify(nextResponse), { status: 200, headers: { "content-type": "application/json" } });
};

before(async () => {
  server = createApp({
    tokens: ["tok-1"], provider: "gemini", geminiKey: "gm-key", geminiModel: "gemini-2.5-flash",
    llmFetch: fakeGeminiFetch, ratePerMin: 50, log: { error() {} },
  });
  await new Promise((r) => server.listen(0, r));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const tools = [{
  name: "select_next_track",
  description: "Queue a track",
  input_schema: {
    type: "object",
    properties: {
      track_id: { type: "string", maxLength: 500 },
      energy_delta: { type: "number", minimum: -0.5, maximum: 0.5 },
      genres: { type: "array", maxItems: 6, items: { type: "string", maxLength: 80 } },
    },
    required: ["track_id"],
    additionalProperties: false,
  },
}];

const post = (body) => fetch(`${base}/v1/claude/messages`, {
  method: "POST", headers: { "content-type": "application/json", authorization: "Bearer tok-1" }, body: JSON.stringify(body),
});

test("health reports the gemini provider and model", async () => {
  const j = await (await fetch(`${base}/health`)).json();
  assert.equal(j.provider, "gemini");
  assert.equal(j.model, "gemini-2.5-flash");
});

test("schema translation keeps the supported subset and drops the rest", () => {
  const g = schemaToGemini(tools[0].input_schema);
  assert.equal(g.type, "OBJECT");
  assert.equal(g.properties.track_id.type, "STRING");
  assert.equal(g.properties.track_id.maxLength, undefined);
  assert.equal(g.properties.energy_delta.minimum, -0.5);
  assert.equal(g.properties.genres.items.type, "STRING");
  assert.deepEqual(g.required, ["track_id"]);
  assert.equal(g.additionalProperties, undefined);
});

test("a functionCall answer comes back as a Messages-API tool_use turn", async () => {
  captured.length = 0;
  nextResponse = {
    candidates: [{ content: { role: "model", parts: [
      { text: "Picking a banger." },
      { functionCall: { name: "select_next_track", args: { track_id: "abc" } } },
    ] }, finishReason: "STOP" }],
    usageMetadata: { promptTokenCount: 100, candidatesTokenCount: 20 },
  };
  const r = await post({ purpose: "decision", system: "You are a DJ.", messages: [{ role: "user", content: "state" }], tools });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.stop_reason, "tool_use");
  assert.equal(j.content[0].type, "text");
  assert.equal(j.content[1].type, "tool_use");
  assert.equal(j.content[1].name, "select_next_track");
  assert.ok(j.content[1].id.startsWith("toolu_g"));
  assert.deepEqual(j.content[1].input, { track_id: "abc" });
  // And the request Gemini saw:
  const req = captured[0];
  assert.match(req.url, /models\/gemini-2\.5-flash:generateContent$/);
  assert.equal(req.headers["x-goog-api-key"], "gm-key");
  assert.equal(req.body.systemInstruction.parts[0].text, "You are a DJ.");
  assert.equal(req.body.tools[0].functionDeclarations[0].name, "select_next_track");
  assert.equal(req.body.generationConfig.maxOutputTokens, 2000);
});

test("tool results round-trip as functionResponse parts matched by tool_use id", async () => {
  captured.length = 0;
  nextResponse = { candidates: [{ content: { role: "model", parts: [{ text: "Queued." }] }, finishReason: "STOP" }] };
  const history = [
    { role: "user", content: "state" },
    { role: "assistant", content: [
      { type: "tool_use", id: "toolu_g1_0", name: "select_next_track", input: { track_id: "abc" } },
      { type: "tool_use", id: "toolu_g1_1", name: "select_next_track", input: { track_id: "zzz" } },
    ] },
    { role: "user", content: [
      { type: "tool_result", tool_use_id: "toolu_g1_0", content: "{\"ok\":true}" },
      { type: "tool_result", tool_use_id: "toolu_g1_1", content: "no such track", is_error: true },
    ] },
  ];
  const r = await post({ purpose: "command", system: "sys", messages: history, tools });
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.stop_reason, "end_turn");
  assert.equal(j.content[0].text, "Queued.");
  const contents = captured[0].body.contents;
  assert.equal(contents.length, 3);
  assert.equal(contents[1].role, "model");
  assert.equal(contents[1].parts[0].functionCall.name, "select_next_track");
  assert.equal(contents[2].role, "user");
  assert.deepEqual(contents[2].parts[0].functionResponse, { name: "select_next_track", response: { ok: true } });
  assert.deepEqual(contents[2].parts[1].functionResponse, { name: "select_next_track", response: { text: "no such track" } });
  assert.equal(captured[0].body.generationConfig.maxOutputTokens, 4000);
});

test("safety blocks map to a refusal stop reason", () => {
  const j = fromGeminiResponse({ promptFeedback: { blockReason: "SAFETY" } }, "gemini-2.5-flash");
  assert.equal(j.stop_reason, "refusal");
  assert.deepEqual(j.content, []);
});

test("MAX_TOKENS maps to max_tokens", () => {
  const j = fromGeminiResponse({ candidates: [{ content: { parts: [{ text: "half an ans" }] }, finishReason: "MAX_TOKENS" }] }, "m");
  assert.equal(j.stop_reason, "max_tokens");
});

test("toGeminiRequest skips thinking blocks and empty text", () => {
  const req = toGeminiRequest({
    system: "s",
    messages: [
      { role: "user", content: "hi" },
      { role: "assistant", content: [{ type: "thinking", thinking: "", signature: "x" }, { type: "text", text: "yo" }] },
      { role: "user", content: "next" },
    ],
    tools: [],
  }, { maxOutputTokens: 100 });
  assert.equal(req.contents.length, 3);
  assert.deepEqual(req.contents[1].parts, [{ text: "yo" }]);
  assert.equal(req.tools, undefined);
});
