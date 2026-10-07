const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const source = fs.readFileSync(path.join(__dirname, "..", "index.js"), "utf8");
const TTL = 3600 * 1000;

test("Deployment manifest and lockfile agree on the supported Node.js 22 runtime", () => {
  const functionsDir = path.join(__dirname, "..");
  const pkg = JSON.parse(fs.readFileSync(path.join(functionsDir, "package.json"), "utf8"));
  const lock = JSON.parse(fs.readFileSync(path.join(functionsDir, "package-lock.json"), "utf8"));
  const config = JSON.parse(fs.readFileSync(path.join(functionsDir, "..", "firebase.json"), "utf8"));
  assert.equal(pkg.engines.node, "22");
  assert.equal(lock.packages[""].engines.node, "22");
  assert.equal(config.functions.runtime, "nodejs22");
});

function createHarness() {
  const state = { now: TTL, promptReads: 0, prompt: "Crux test prompt", failPrompt: false, calls: [] };
  class HttpsError extends Error {
    constructor(code, message) { super(message); this.code = code; }
  }
  const mocks = {
    "firebase-functions/v2/https": {
      HttpsError,
      onCall(options, handler) { state.options = options; return handler; },
    },
    "firebase-functions/params": {
      defineSecret(name) {
        assert.equal(name, "GEMINI_SECRET_KEY");
        return { value: () => "test-placeholder-not-a-real-key" };
      },
    },
    "firebase-admin": {
      initializeApp() {},
      remoteConfig: () => ({ getTemplate: async () => {
        state.promptReads++;
        await new Promise(setImmediate);
        if (state.failPrompt) throw new Error("Simulated Remote Config failure");
        return { parameters: { system_prompt: { defaultValue: { value: state.prompt } } } };
      } }),
      app: () => ({ options: { projectId: "cruxai-summarize" } }),
      storage: () => ({ bucket: (bucket) => {
        assert.equal(bucket, "cruxai-summarize.firebasestorage.app");
        return { file: (filePath) => {
          assert.equal(filePath, "uploads/test-user/test.pdf");
          return {
            getMetadata: async () => [{ size: "8", contentType: "application/pdf" }],
            download: async () => [Buffer.from("test pdf")],
          };
        } };
      } }),
    },
    "@google/genai": {
      GoogleGenAI: class {
        constructor() {
          this.models = { generateContent: async (request) => {
            state.calls.push(JSON.parse(JSON.stringify(request)));
            return { candidates: [{ content: { parts: [{ text: " test answer " }] } }] };
          } };
        }
      },
    },
  };
  const context = {
    exports: {},
    require: (name) => { assert.ok(mocks[name], `Unexpected dependency: ${name}`); return mocks[name]; },
    console: { log() {}, warn() {}, error() {} },
    process: { env: { GCLOUD_PROJECT: "cruxai-summarize" } },
    Date: { now: () => state.now },
    URL, Buffer,
  };
  vm.runInNewContext(source, context, { filename: "functions/index.js" });
  state.handler = context.exports.processGemini;
  state.send = (data = { text: "Test" }) => state.handler({ auth: { uid: "test-user" }, data });
  return state;
}

test("Gemini config preserves prompt/token cap and omits deprecated fields for all input types", async (t) => {
  const cases = [
    ["text/history", { text: "Summarize this", history: [{ role: "model", text: "Previous answer" }] }],
    ["image", { text: "Describe image", mediaType: "IMAGE", mediaBase64: "dGVzdA==" }],
    ["audio", { mediaType: "AUDIO", mediaBase64: "dGVzdA==" }],
    ["inline PDF", { mediaType: "DOC", mediaBase64: "dGVzdA==" }],
    ["Storage PDF", { mediaType: "DOC", mediaUrl: "https://firebasestorage.googleapis.com/v0/b/cruxai-summarize.firebasestorage.app/o/uploads%2Ftest-user%2Ftest.pdf" }],
  ];
  for (const [name, data] of cases) {
    await t.test(name, async () => {
      const state = createHarness();
      assert.equal((await state.send(data)).response, "test answer");
      const request = state.calls[0];
      assert.equal(request.model, "gemini-3.5-flash-lite");
      assert.deepEqual(request.config, { systemInstruction: "Crux test prompt", maxOutputTokens: 4096 });
      const parts = request.contents.at(-1).parts;
      if (data.text) assert.equal(parts[0].text, data.text);
      if (data.history) assert.deepEqual(request.contents[0], { role: "model", parts: [{ text: "Previous answer" }] });
      if (data.mediaType) {
        const media = parts.at(-1).inlineData;
        assert.equal(media.mimeType, { IMAGE: "image/jpeg", AUDIO: "audio/mp3", DOC: "application/pdf" }[data.mediaType]);
        assert.equal(media.data, data.mediaBase64 || Buffer.from("test pdf").toString("base64"));
      }
    });
  }
});

test("Concurrent cold requests and expired-cache requests share one Remote Config refresh", async () => {
  const state = createHarness();
  await Promise.all(Array.from({ length: 50 }, () => state.send()));
  assert.equal(state.promptReads, 1);
  state.now += TTL - 1;
  await state.send();
  assert.equal(state.promptReads, 1);
  state.now += 1;
  state.prompt = "Updated prompt";
  await Promise.all(Array.from({ length: 50 }, () => state.send()));
  assert.equal(state.promptReads, 2);
  assert.ok(state.calls.slice(-50).every((call) => call.config.systemInstruction === "Updated prompt"));
});

test("Failed refresh preserves the cached prompt and permits the next refresh", async () => {
  const state = createHarness();
  await state.send();
  state.now += TTL;
  state.failPrompt = true;
  await Promise.all(Array.from({ length: 10 }, () => state.send()));
  assert.equal(state.promptReads, 2);
  assert.equal(state.calls.at(-1).config.systemInstruction, "Crux test prompt");
  state.failPrompt = false;
  state.prompt = "Recovered prompt";
  state.now += TTL;
  await state.send();
  assert.equal(state.promptReads, 3);
  assert.equal(state.calls.at(-1).config.systemInstruction, "Recovered prompt");
});

test("Cold Remote Config failure and blank prompt retain the original fallback", async () => {
  for (const failPrompt of [true, false]) {
    const state = createHarness();
    state.failPrompt = failPrompt;
    state.prompt = "  ";
    await state.send();
    assert.equal(state.calls[0].config.systemInstruction, "You are CruxAI. Help the user safely.");
  }
});

test("Existing auth, input validation and function settings are preserved", async () => {
  const state = createHarness();
  assert.equal(state.options.memory, "512MiB");
  assert.equal(state.options.timeoutSeconds, 60);
  assert.equal(state.options.maxInstances, 50);
  await assert.rejects(state.handler({ data: { text: "No auth" } }), { code: "unauthenticated" });
  await assert.rejects(state.send({}), { code: "invalid-argument" });
  await assert.rejects(state.send({ text: "Test", mediaType: "UNSUPPORTED" }), { code: "invalid-argument" });
  assert.equal(state.calls.length, 0);
});
