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
const MAX_TEXT_CHARS = 50_000;
const MAX_HISTORY_ITEMS = 24;
const MAX_HISTORY_TEXT_CHARS = 8_000;
const MAX_MEDIA_BYTES = 10 * 1024 * 1024;
const MAX_BASE64_CHARS = 14_000_000;
const ALLOWED_MEDIA_TYPES = new Set(["IMAGE", "AUDIO", "DOC"]);

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
    for (const msg of history.slice(-MAX_HISTORY_ITEMS)) {
      if (!msg || typeof msg.text !== "string" || msg.text.trim().length === 0) continue;
      const role = msg.role === "model" ? "model" : "user";
      contents.push({
        role,
        parts: [{ text: msg.text.slice(0, MAX_HISTORY_TEXT_CHARS) }],
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

function parseOwnedStorageUrl(mediaUrl, uid) {
  let parsed;
  try {
    parsed = new URL(mediaUrl);
  } catch (_) {
    throw new HttpsError("invalid-argument", "Invalid media URL.");
  }

  if (parsed.protocol !== "https:" || parsed.hostname !== "firebasestorage.googleapis.com") {
    throw new HttpsError("permission-denied", "Only Crux AI Storage media is accepted.");
  }

  const match = parsed.pathname.match(/^\/v0\/b\/([^/]+)\/o\/(.+)$/);
  if (!match) {
    throw new HttpsError("invalid-argument", "Invalid Storage URL.");
  }

  const bucketName = decodeURIComponent(match[1]);
  const path = decodeURIComponent(match[2]);
  const projectId = process.env.GCLOUD_PROJECT || admin.app().options.projectId;
  const allowedBuckets = new Set([
    `${projectId}.appspot.com`,
    `${projectId}.firebasestorage.app`,
  ]);

  if (!allowedBuckets.has(bucketName) || !path.startsWith(`uploads/${uid}/`)) {
    throw new HttpsError("permission-denied", "Media does not belong to this user.");
  }

  return { bucketName, path };
}

// ─── MAIN CLOUD FUNCTION ────────────────────────────────────────
exports.processGemini = onCall(
  {
    memory: "512MiB",
    timeoutSeconds: 60,
    maxInstances: 50,
    secrets: [geminiApiKey],
    // Keep disabled until Play Integrity tokens are verified on a production-signed build.
    // Auth, ownership checks and strict payload limits below remain mandatory.
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

    const data = request.data && typeof request.data === "object" ? request.data : {};
    const text = typeof data.text === "string" ? data.text.slice(0, MAX_TEXT_CHARS) : "";
    const mediaBase64 = typeof data.mediaBase64 === "string" ? data.mediaBase64 : null;
    const mediaUrl = typeof data.mediaUrl === "string" ? data.mediaUrl : null;
    const mediaType = typeof data.mediaType === "string" ? data.mediaType : null;
    const mimeType = typeof data.mimeType === "string" ? data.mimeType.slice(0, 100) : null;
    const history = Array.isArray(data.history) ? data.history : [];

    // ── Validation ──
    const hasText = text.trim().length > 0;
    const hasMedia = (mediaBase64 || mediaUrl) && mediaType;

    if (!hasText && !hasMedia) {
      throw new HttpsError("invalid-argument", "No content provided.");
    }
    if (mediaType && !ALLOWED_MEDIA_TYPES.has(mediaType)) {
      throw new HttpsError("invalid-argument", "Unsupported media type.");
    }
    if (mediaBase64 && mediaBase64.length > MAX_BASE64_CHARS) {
      throw new HttpsError("invalid-argument", "Media payload too large.");
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
          const { bucketName, path } = parseOwnedStorageUrl(mediaUrl, request.auth.uid);
          const file = admin.storage().bucket(bucketName).file(path);
          const [metadata] = await file.getMetadata();
          const fileSize = Number(metadata.size || 0);
          if (!Number.isFinite(fileSize) || fileSize <= 0 || fileSize > MAX_MEDIA_BYTES) {
            throw new HttpsError("invalid-argument", "Media file is empty or too large.");
          }
          const [fileBuffer] = await file.download();
          finalMediaBase64 = fileBuffer.toString("base64");
          resolvedMimeType = metadata.contentType || resolveMimeType(mediaType, null);
        } catch (err) {
          if (err instanceof HttpsError) throw err;
          console.error("Error downloading media resource:", err);
          throw new HttpsError("internal", "Failed to retrieve media file.");
        }
      }

      if (finalMediaBase64 && finalMediaBase64.length > MAX_BASE64_CHARS) {
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
      const responseText = response?.candidates?.[0]?.content?.parts
        ?.map((part) => typeof part.text === "string" ? part.text : "")
        .join("")
        .trim();

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

