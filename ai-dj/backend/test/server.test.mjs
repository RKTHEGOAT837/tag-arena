// Backend tests. No real Claude calls are made: the SDK is given a fake fetch, so these tests check
// the exact HTTP request the official SDK sends to the Messages API, plus auth/validation/proxy logic.
import Anthropic from "@anthropic-ai/sdk";
import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { createApp } from "../server.mjs";

let server, base;
const captured = [];

const cannedMessage = {
  id: "msg_test", type: "message", role: "assistant", model: "claude-opus-5-5", stop_reason: "tool_use", stop_sequence: null,
  content: [{ type: "tool_use", id: "toolu_1", name: "select_next_track", input: { track_id: "abc", genres: [], reason: "test" } }],
  usage: { input_tokens: 10, output_tokens: 5 },
};

const fakeFetch = async (url, init) => {
  captured.push({ url: String(url), headers: new Headers(init.headers), body: JSON.parse(init.body) });
  return new Response(JSON.stringify(cannedMessage), { status: 200, headers: { "content-type": "application/json", "request-id": "req_1" } });
};

const jamendoFetch = async (url) => {
  captured.push({ url: String(url) });
  return new Response(JSON.stringify({ headers: { status: "success" }, results: [] }), { status: 200 });
};

before(async () => {
  const client = new Anthropic({ apiKey: "sk-ant-test", fetch: fakeFetch, maxRetries: 0 });
  server = createApp({ client, tokens: ["dev-token-123"], jamendoClientId: "jam-id", fetchImpl: jamendoFetch, ratePerMin: 5, log: { error() {} } });
  await new Promise((r) => server.listen(0, r));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(() => server.close());

const turn = {
  purpose: "decision",
  system: "You are a DJ.",
  messages: [{ role: "user", content: "state..." }],
  tools: [{ name: "select_next_track", description: "Queue a track", input_schema: { type: "object", properties: { track_id: { type: "string" } }, required: ["track_id"], additionalProperties: false } }],
};

const post = (body, token = "dev-token-123") => fetch(`${base}/v1/claude/messages`, {
  method: "POST", headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) }, body: JSON.stringify(body),
});

test("health is public and reports configuration", async () => {
  const r = await fetch(`${base}/health`);
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.ok, true); assert.equal(j.jamendo, true); assert.equal(j.model, "claude-opus-5-5");
});

test("rejects missing or wrong tokens", async () => {
  assert.equal((await post(turn, null)).status, 401);
  assert.equal((await post(turn, "dev-token-124")).status, 401);
});

test("validates the turn shape", async () => {
  assert.equal((await post({ ...turn, purpose: "anything" })).status, 400);
  assert.equal((await post({ ...turn, tools: [{ name: "bad name", description: "", input_schema: { type: "object" } }] })).status, 400);
  assert.equal((await post({ ...turn, messages: [{ role: "assistant", content: "x" }] })).status, 400);
});

test("forwards a well-formed Messages API request via the official SDK and returns the response", async () => {
  captured.length = 0;
  const r = await post(turn);
  assert.equal(r.status, 200);
  const j = await r.json();
  assert.equal(j.content[0].name, "select_next_track");
  const req = captured.find((c) => c.url.includes("/v1/messages"));
  assert.ok(req, "SDK called the Messages endpoint");
  assert.equal(req.body.model, "claude-opus-5-5");
  assert.equal(req.body.max_tokens, 4000);
  assert.deepEqual(req.body.output_config, { effort: "low" });
  assert.equal(req.body.fallbacks, "default");
  assert.deepEqual(req.body.tool_choice, { type: "auto" });
  assert.equal(req.body.tools[0].name, "select_next_track");
  assert.match(req.headers.get("anthropic-beta"), /server-side-fallback-2026-07-01/);
  assert.ok((req.headers.get("x-api-key") ?? req.headers.get("authorization") ?? "").includes("sk-ant-test"), "API key sent by the SDK");
  assert.equal(req.body.thinking, undefined, "no thinking config is sent (Opus 5.5 runs adaptive thinking)");
});

test("proxies Jamendo with the server-side client id and only allowed params", async () => {
  captured.length = 0;
  const r = await fetch(`${base}/v1/jamendo/tracks?search=house&limit=5&client_id=evil&format=json`, { headers: { authorization: "Bearer dev-token-123" } });
  assert.equal(r.status, 200);
  const u = new URL(captured[0].url);
  assert.equal(u.searchParams.getAll("client_id").length, 1);
  assert.equal(u.searchParams.get("client_id"), "jam-id");
  assert.equal(u.searchParams.get("search"), "house");
});

test("rate limits per device token", async () => {
  let limited = false;
  for (let i = 0; i < 8; i++) if ((await post(turn)).status === 429) limited = true;
  assert.ok(limited);
});
