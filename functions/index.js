const { onCall, HttpsError } = require("firebase-functions/v2/https");
const { defineSecret } = require("firebase-functions/params");
const admin = require("firebase-admin");
const { GoogleGenAI } = require("@google/genai");

admin.initializeApp();

// ─── CONFIG ──────────────────────────────────────────────────────
const geminiApiKey = defineSecret("GEMINI_SECRET_KEY");
const MODEL_NAME = "gemini-3.1-flash-lite";
const MAX_OUTPUT_TOKENS = 4096;
const TEMPERATURE = 0.4;
const TOP_P = 0.9;
const DEFAULT_SYSTEM_PROMPT = "You are CruxAI. Help the user safely.";

// ─── REMOTE CONFIG CACHE ────────────────────────────────────────
let cachedSystemPrompt = null;
let promptLastFetched = 0;
const PROMPT_CACHE_TTL = 3600 * 1000;

async function getSystemPrompt() {
  const now = Date.now();
  if (cachedSystemPrompt !== null && now - promptLastFetched < PROMPT_CACHE_TTL) {
    return cachedSystemPrompt;
  }

  try {
    const rc = admin.remoteConfig();
    const template = await rc.getTemplate();
    const param = template.parameters["system_prompt"];
    if (param && param.defaultValue && param.defaultValue.value) {
      const val = param.defaultValue.value.trim();
      cachedSystemPrompt = val.length > 0 ? val : DEFAULT_SYSTEM_PROMPT;
    } else {
      cachedSystemPrompt = DEFAULT_SYSTEM_PROMPT;
    }
  } catch (err) {
    console.warn("Remote Config fetch failed, using default:", err.message);
    cachedSystemPrompt = cachedSystemPrompt || DEFAULT_SYSTEM_PROMPT;
  }

  promptLastFetched = now;
  return cachedSystemPrompt;
}

// ─── MIME TYPE RESOLVER ─────────────────────────────────────────
function resolveMimeType(mediaType, mimeType) {
  if (mimeType) return mimeType;
  switch (mediaType) {
    case "IMAGE": return "image/jpeg";
    case "AUDIO": return "audio/mp3";
    case "DOC": return "application/pdf";
    default: return "application/octet-stream";
  }
}

// ─── BUILD CONTENTS ARRAY ───────────────────────────────────────
function buildContents(text, mediaBase64, mediaType, mimeType, history) {
  const contents = [];

  // 1. Add chat history (if any)
  if (Array.isArray(history) && history.length > 0) {
    for (const msg of history) {
      if (!msg.role || !msg.text) continue;
      const role = msg.role === "model" ? "model" : "user";
      contents.push({
        role,
        parts: [{ text: msg.text }],
      });
    }
  }

  // 2. Build the current user message parts
  const parts = [];

  if (text && text.trim().length > 0) {
    parts.push({ text });
  }

  if (mediaBase64 && mediaType) {
    const resolvedMime = resolveMimeType(mediaType, mimeType);
    parts.push({
      inlineData: {
        data: mediaBase64,
        mimeType: resolvedMime,
      },
    });
  }

  if (parts.length > 0) {
    contents.push({
      role: "user",
      parts,
    });
  }

  return contents;
}

// ─── MAIN CLOUD FUNCTION ────────────────────────────────────────
exports.processGemini = onCall(
  {
    memory: "512MiB",
    timeoutSeconds: 60,
    maxInstances: 50,
    secrets: [geminiApiKey],
    // enforceAppCheck DEVRE DIŞI — App Check token sorunu UNAUTHENTICATED hatasına yol açıyordu
    enforceAppCheck: false,
  },
  async (request) => {
    // ── Detaylı log ──
    console.log("processGemini called. Auth present:", !!request.auth);
    if (request.auth) {
      console.log("Auth UID:", request.auth.uid);
    }

    // ── Auth check ──
    if (!request.auth) {
      console.error("AUTH FAILED: request.auth is null. User is not signed in or token expired.");
      throw new HttpsError("unauthenticated", "Authentication required.");
    }

    const { text, mediaBase64, mediaUrl, mediaType, mimeType, history } = request.data;

    // ── Validation ──
    const hasText = text && text.trim().length > 0;
    const hasMedia = (mediaBase64 || mediaUrl) && mediaType;

    if (!hasText && !hasMedia) {
      throw new HttpsError("invalid-argument", "No content provided.");
    }

    try {
      // ── API Key'i runtime'da al ──
      const apiKey = geminiApiKey.value();
      if (!apiKey) {
        console.error("CRITICAL: GEMINI_API_KEY secret is not set!");
        throw new HttpsError("internal", "Server configuration error.");
      }

      let finalMediaBase64 = mediaBase64;
      let resolvedMimeType = mimeType;

      if (mediaUrl && mediaType) {
        try {
          if (mediaUrl.includes("/o/")) {
            const pathEncoded = mediaUrl.split("/o/")[1].split("?")[0];
            const path = decodeURIComponent(pathEncoded);
            console.log(`Downloading from storage path: ${path}`);
            let bucket;
            if (mediaUrl.includes("/v0/b/")) {
              const bucketName = mediaUrl.split("/v0/b/")[1].split("/o/")[0];
              bucket = admin.storage().bucket(bucketName);
            } else {
              bucket = admin.storage().bucket();
            }
            const [fileBuffer] = await bucket.file(path).download();
            finalMediaBase64 = fileBuffer.toString("base64");

            if (!resolvedMimeType) {
              if (path.endsWith(".jpg") || path.endsWith(".jpeg")) resolvedMimeType = "image/jpeg";
              else if (path.endsWith(".png")) resolvedMimeType = "image/png";
              else if (path.endsWith(".gif")) resolvedMimeType = "image/gif";
              else if (path.endsWith(".webp")) resolvedMimeType = "image/webp";
              else if (path.endsWith(".pdf")) resolvedMimeType = "application/pdf";
              else if (path.endsWith(".mp3")) resolvedMimeType = "audio/mp3";
              else if (path.endsWith(".wav")) resolvedMimeType = "audio/wav";
              else if (path.endsWith(".m4a")) resolvedMimeType = "audio/m4a";
              else if (path.endsWith(".txt")) resolvedMimeType = "text/plain";
              else resolvedMimeType = "application/octet-stream";
            }
          } else {
            console.log(`Downloading from external URL: ${mediaUrl}`);
            const res = await fetch(mediaUrl);
            if (!res.ok) throw new Error(`Fetch failed with status ${res.status}`);
            const arrayBuffer = await res.arrayBuffer();
            finalMediaBase64 = Buffer.from(arrayBuffer).toString("base64");
            if (!resolvedMimeType) {
              resolvedMimeType = res.headers.get("content-type") || "application/octet-stream";
            }
          }
        } catch (err) {
          console.error("Error downloading media resource:", err);
          throw new HttpsError("internal", `Failed to retrieve media file: ${err.message}`);
        }
      }

      // ── Payload size guard (10MB base64 ≈ 7.5MB binary) ──
      if (finalMediaBase64 && finalMediaBase64.length > 14_000_000) {
        throw new HttpsError("invalid-argument", "Media payload too large (max ~10MB).");
      }

      const ai = new GoogleGenAI({ apiKey });

      const systemPrompt = await getSystemPrompt();
      const contents = buildContents(text, finalMediaBase64, mediaType, resolvedMimeType, history);

      if (contents.length === 0) {
        throw new HttpsError("invalid-argument", "Empty content after processing.");
      }

      const response = await ai.models.generateContent({
        model: MODEL_NAME,
        contents,
        config: {
          systemInstruction: systemPrompt,
          maxOutputTokens: MAX_OUTPUT_TOKENS,
          temperature: TEMPERATURE,
          topP: TOP_P,
        },
      });

      // ── Extract text from response ──
      const responseText = response?.candidates?.[0]?.content?.parts?.[0]?.text;

      if (!responseText || responseText.trim().length === 0) {
        // Check for safety block
        const finishReason = response?.candidates?.[0]?.finishReason;

        if (finishReason === "SAFETY") {
          throw new HttpsError("permission-denied", "SAFETY_BLOCKED");
        }
        if (finishReason === "RECITATION") {
          throw new HttpsError("permission-denied", "RECITATION_BLOCKED");
        }

        throw new HttpsError("internal", "EMPTY_RESPONSE");
      }

      return { response: responseText };
    } catch (err) {
      // Re-throw HttpsErrors as-is
      if (err instanceof HttpsError) {
        throw err;
      }

      console.error("Gemini API error:", err.message, err.stack);

      const msg = (err.message || "").toLowerCase();

      if (msg.includes("429") || msg.includes("quota")) {
        throw new HttpsError("resource-exhausted", "QUOTA_EXCEEDED");
      }
      if (msg.includes("401") || msg.includes("403") || msg.includes("api key")) {
        throw new HttpsError("permission-denied", "AUTH_ERROR");
      }
      if (msg.includes("503") || msg.includes("overloaded") || msg.includes("unavailable")) {
        throw new HttpsError("unavailable", "SERVICE_UNAVAILABLE");
      }
      if (msg.includes("504") || msg.includes("timeout") || msg.includes("deadline")) {
        throw new HttpsError("deadline-exceeded", "TIMEOUT");
      }
      if (msg.includes("safety") || msg.includes("blocked")) {
        throw new HttpsError("permission-denied", "SAFETY_BLOCKED");
      }
      if (msg.includes("404")) {
        throw new HttpsError("not-found", "MODEL_NOT_FOUND");
      }

      throw new HttpsError("internal", "UNKNOWN_ERROR");
    }
  }
);

