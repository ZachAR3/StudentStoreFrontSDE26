# StudentStoreFront — WhatsApp Bot Documentation

## Table of Contents
1. [Overview](#1-overview)
2. [Tech Stack & Libraries](#2-tech-stack--libraries)
3. [Project Structure](#3-project-structure)
4. [Core Architecture & Services](#4-core-architecture--services)
5. [Key Workflows](#5-key-workflows)
6. [Configuration & Environment](#6-configuration--environment)
7. [Developer Guide](#7-developer-guide)

---

## 1. Overview

The WhatsApp bot is an AI-powered bridge between a WhatsApp group chat and the Student Storefront Spring Boot backend. Its primary purpose is to allow students to list items for sale simply by chatting in a designated WhatsApp group — **no web form required**.

**How it works at a high level:**
1. The bot monitors a specific WhatsApp group for messages.
2. After 5 minutes of user inactivity, it sends the accumulated messages to Google Gemini to determine whether they represent a marketplace listing.
3. If Gemini says yes, it DMs the user to ask for consent.
4. Once consent is granted, it uploads any images to Cloudinary, parses the listing details with Gemini, and posts the result to the Spring Boot backend via a REST API.

The bot also handles WhatsApp-based QR login, allowing users to authenticate on the web app by scanning a QR code and then DMing the bot a confirmation token.

---

## 2. Tech Stack & Libraries

| Library | Version | Role |
|---|---|---|
| [`whatsapp-web.js`](https://wwebjs.dev/) | ^1.34.6 | Core WhatsApp automation framework. Controls the WhatsApp Web client via Puppeteer. |
| [`@google/generative-ai`](https://ai.google.dev/) | ^0.24.1 | Google Gemini SDK. Used for both classifying messages as listings and parsing unstructured text into structured JSON. |
| [`openai`](https://www.npmjs.com/package/openai) | ^6.0.0 | OpenAI-compatible SDK used against LiteLLM/OpenAI-style endpoints. |
| [`cloudinary`](https://cloudinary.com/documentation/node_integration) | ^2.9.0 | Cloud image hosting. Images sent in WhatsApp are uploaded here; the resulting CDN URLs are stored with the listing. |
| [`express`](https://expressjs.com/) | ^5.1.0 | Minimal HTTP server for the webhook endpoint that Spring Boot calls after a user registers. |
| [`axios`](https://axios-http.com/) | ^1.14.0 | HTTP client for all Spring Boot API calls. |
| [`dotenv`](https://github.com/motdotla/dotenv) | ^17.4.1 | Loads `.env` variables into `process.env`. |
| [`qrcode-terminal`](https://github.com/gtanner/qrcode-terminal) | ^0.12.0 | Renders the WhatsApp QR code directly in the terminal for initial authentication. |
| [`langfuse`](https://langfuse.com) | ^3.38.20 | LLM observability — traces every Gemini/Gemma call with latency, token usage, and errors. |

### Why `whatsapp-web.js`?

`whatsapp-web.js` drives an actual WhatsApp Web session via Puppeteer (headless Chromium). It is not an official API — it reverse-engineers the WhatsApp Web interface. This means:
- A phone number **must** be linked to it once via QR code scan.
- The session can be persisted locally so the bot does not need to re-scan on restart (`LocalAuth`).
- Rate limits and account bans are a real risk if the bot sends too many messages too quickly.

---

## 3. Project Structure

```
bot/
├── src/
│   ├── WhatsappBot.js              # Entry point — client lifecycle + message handler
│   ├── services/llm/
│   │   ├── modelRouter.js          # Multi-provider routing (primary + fallback chain)
│   │   └── providers/
│   │       ├── geminiProvider.js   # Gemini provider implementation
│   │       └── openaiCompatibleProvider.js # LiteLLM/OpenAI-style provider implementation
│   ├── services/
│   │   ├── botGeminiService.js     # Gemini classification & parsing (production LLM)
│   │   ├── Gemma4Service.js        # Gemma 4 via local Ollama (benchmark/testing only)
│   │   ├── langfuseService.js      # Langfuse singleton for LLM observability tracing
│   │   ├── springServices.js       # All Spring Boot REST API calls
│   │   ├── webhookService.js       # Express server for the seller-registered webhook
│   │   ├── claudinary.js           # Cloudinary image upload helper
│   │   └── Prompt-File.js          # Gemini prompt templates (classification + parsing)
│   └── tests/
│       ├── GeminiAPI-test-Mock.js  # Standalone test harness with mocked services
│       └── runBenchmark.js         # Head-to-head Gemini vs Gemma benchmark (requires Ollama)
├── consentedUsersPersistence.json  # Persisted list of phone numbers that gave consent
├── .env                            # Local secrets (not committed)
├── .env.example                    # Template showing required variables
└── package.json
```

---

## 4. Core Architecture & Services

### 4.1 `WhatsappBot.js` — Entry Point

This is the main file. It owns the WhatsApp client, all in-memory state, and the top-level message handler.

**Client initialization:**
```js
const client = new Client({ authStrategy: new LocalAuth() })
client.on('qr', qr => qrcode.generate(qr, { small: true }))
client.once('ready', () => console.log('Bot is ready!'))
client.initialize()
```
`LocalAuth` saves the WhatsApp session to disk (`.wwebjs_auth/` folder) so re-authentication is not needed on restart.

**In-memory state:**

| Variable | Type | Purpose |
|---|---|---|
| `userState` | `Map<phone, UserState>` | Tracks each user's active listing buffer, timers, and flags |
| `consentedUsers` | `Set<phone>` | Phone numbers that have consented; persisted to disk |
| `NOT_CONSENTED_TO_MESSAGE_UPLOAD` | `Set<phone>` | Phone numbers that explicitly declined; held in memory only |

The `UserState` object shape:
```js
{
  listing: {
    imageUrls: [],       // Array of { data: base64String, mimetype: 'image/jpeg' }
    messages: [],        // Array of text message strings
    createdAt: Date.now(),
    isListing: false     // Set to true after Gemini confirms it's a listing
  },
  consentPending: false,       // True while waiting for YES/NO DM reply
  registrationPending: false,  // True while waiting for the user to register
  timer: null                  // The active setTimeout handle
}
```

**Stale listing cleanup:**
A `setInterval` runs every 3 hours and removes entries from `userState` whose `createdAt` is older than 12 hours (`LISTING_EXPIRY_HOURS`). This prevents memory leaks from users who never reply.

---

### 4.2 `botGeminiService.js` — AI Classification & Parsing (Production)

All production Gemini logic is isolated here. It exposes two functions:

#### `GeminiContextClassifier(messages: string[]) → 'YES' | 'NO'`

Takes an array of raw message strings from a single user and asks Gemini whether they collectively represent a marketplace listing.

- Uses the `classificationPrompt` from `Prompt-File.js`.
- Returns `'YES'` if the response contains "YES", otherwise `'NO'`.
- On error, fails safely to `'NO'` so the bot never crashes on a Gemini timeout.

#### `GeminiMessageParser(message: string) → Object | null`

Takes the joined message text and asks Gemini to extract structured listing fields.

- Uses the `UserMessagePrompt` from `Prompt-File.js`.
- Strips any surrounding markdown from the response and parses the JSON.
- Returns an object `{ title, price, description, category }` or `null` on failure.

**Model fallback chain:** `gemini-2.5-flash-lite` → `gemini-2.0-flash-lite` → `gemini-1.5-flash-8b` → `gemini-1.5-flash`

If the primary model fails for any reason (403, quota exceeded, rate limit, network error), the next model in the chain automatically takes over. A warning is printed to the console on each fallback. If all models fail, a final error is logged and the function returns `null`/`'NO'` safely.

### 4.2b `services/llm/modelRouter.js` — Multi-Model Routing (Production Path)

The bot runtime now routes model calls through a provider router that supports:
- Configurable primary provider (`LLM_PRIMARY_PROVIDER`)
- Configurable fallback providers (`LLM_FALLBACK_PROVIDERS`, comma-separated)
- Provider-specific implementations under `services/llm/providers/*`

Current providers:
- `gemini`
- `openai-compatible` (for LiteLLM / OpenAI-style endpoints)

This keeps runtime selection in environment config and makes adding a third provider a small, isolated provider-module addition plus registry entry.

---

### 4.3 `Gemma4Service.js` — Local Model (Benchmark & Testing Only)

> **This service is not used by the bot at runtime.** It is only imported by the benchmark script. Teammates without a local Ollama installation are not affected.

`Gemma4Service.js` mirrors the exact interface of `botGeminiService.js` but calls a locally running [Ollama](https://ollama.com) instance instead of the Gemini API. It is used to evaluate whether a local model can replace the cloud API for cost or latency reasons.

- Calls `POST http://localhost:11434/api/generate` with `stream: false`.
- Uses Node's native `http` module — no extra dependencies.
- Exports `GemmaMessageParser` and `GemmaContextClassifier` with the same signatures.
- Full Langfuse tracing is included, identical to `botGeminiService.js`.

**Configuration (optional `.env` overrides):**

| Variable | Default | Description |
|---|---|---|
| `OLLAMA_MODEL` | `gemma4:e4b` | Model name as it appears in `ollama list` |
| `OLLAMA_HOST` | `127.0.0.1` | Ollama server host |
| `OLLAMA_PORT` | `11434` | Ollama server port |

To run Gemma4 locally: `ollama pull gemma4:e4b` then start the benchmark.

---

### 4.4 `langfuseService.js` — LLM Observability

A singleton [Langfuse](https://langfuse.com) client shared across all services. Provides tracing for every LLM call — model name, input prompt, output, latency, token usage (Gemini only), and error details. Traces are visible in the Langfuse dashboard grouped by `sessionId` (phone number) and `userId`.

Required `.env` variables: `LANGFUSE_PUBLIC_KEY`, `LANGFUSE_SECRET_KEY`, `LANGFUSE_BASEURL`.

---

### 4.5 `springServices.js` — Backend API Calls

Three functions, all authenticated via the `X-Bot-Api-Key` header (where required):

#### `getSellerByPhone(phoneNumber) → SellerObject | null`

```
GET /api/sellers/by-phone?phone=+<digits>
```
Normalizes the phone number to digits-only, then prefixes with `+` for the API call. Returns the seller object (containing `sellerId`) or `null` if the seller is not registered (404) or the number is invalid.

#### `createPost(geminiListing, cloudinaryUrls, sellerId) → ResponseData | false`

```
POST /api/posts/bot
X-Bot-Api-Key: <BOT_API_KEY>
```
Creates a marketplace post. Sets `expiresAt` to 48 hours from now. The `category` field is uppercased to match the backend enum.

#### `confirmWhatsAppLogin(loginToken, phoneNumber) → 'OK' | 'EXPIRED' | 'PHONE_NOT_LINKED' | 'ALREADY_USED' | null`

```
POST /api/auth/whatsapp/confirm
X-Bot-Api-Key: <BOT_API_KEY>
```
Used in the QR login flow. Sends the login token + phone number to Spring, which validates and activates the session.

---

### 4.6 `webhookService.js` — Spring-to-Bot Webhook

Starts an Express server that Spring Boot can call **after a user completes registration on the website**. This closes the loop when a user was asked to register before their listing could be processed.

```
POST /webhook/seller-registered
x-bot-api-key: <BOT_API_KEY>
Content-Type: application/json

{ "phoneNumber": "+40712345678" }
```

**Behavior:**
1. Validates the `X-Bot-Api-Key` header.
2. Looks up the phone number in `userState`. Returns 404 if no pending registration exists.
3. Responds immediately with `200 { status: 'processing' }` so Spring is not blocked.
4. Asynchronously calls `processListing` and DMs the user with the result.
5. On success, persists the phone number to `consentedUsers`.

Default port: `3001` (configurable via `BOT_WEBHOOK_PORT`).

---

### 4.7 `claudinary.js` — Image Upload

A thin wrapper around the Cloudinary v2 SDK. Accepts a Base64 data URI and returns the secure CDN URL, or `null` on failure.

```js
// Input format:
`data:image/jpeg;base64,<base64-encoded-bytes>`

// Output:
'https://res.cloudinary.com/<cloud>/image/upload/...'
```

Upload options: `unique_filename: true`, `overwrite: false`. Images are stored permanently unless manually deleted from the Cloudinary dashboard.

---

### 4.8 `Prompt-File.js` — Gemini Prompt Templates

Centralizes both prompt strings, keeping business logic separate from AI instructions.

**`classificationPrompt(messages[])`** — instructs Gemini to reply with only `YES` or `NO`. Provides concrete positive/negative examples to reduce hallucination.

**`UserMessagePrompt(message)`** — instructs Gemini to extract `title`, `price`, `description`, and `category` and return **only** a JSON object with no markdown or extra text. Categories are restricted to: `Electronics`, `Clothing`, `Furniture`, `Books`, `Sports`, `Food`, `Services`, `Other`.

---

## 5. Key Workflows

### 5.1 Listing Detection (Group → Gemini → Post)

```
User sends messages in group
         │
         ▼
Bot accumulates text + images in userState[phone].listing
         │
         │  On every new message:
         ▼
  clearTimeout(previous timer)
  setTimeout(5 min, classifyAndProcess)   ← timer resets on each message
         │
         │  After 5 minutes of silence:
         ▼
  GeminiContextClassifier(messages[]) ──► 'NO'? → delete userState, done
         │
         │  'YES':
         ▼
  User in NOT_CONSENTED set? ──► Yes → skip silently
         │
         │  No — has user consented before?
         ├──► Yes (in consentedUsers) → processListing() directly
         └──► No              → DM user asking for consent
```

> **Note:** The timer is currently set to 30 seconds (`30000 ms`) for development. The `TODO` comment in `WhatsappBot.js:141` marks where to change it back to `300000` (5 minutes) for production.

---

### 5.2 Consent Mechanism (DM Responses)

```
Bot DMs: "Do you consent...? Reply YES or NO."
         │
    ┌────┴────┐
   YES        NO
    │          │
    ▼          ▼
processListing  Add to NOT_CONSENTED_TO_MESSAGE_UPLOAD
    │           Delete userState
    │
    ▼  On success:
Add to consentedUsers Set
Write consentedUsersPersistence.json to disk   ← persistence across restarts
Delete userState
```

**Persistence file format** (`consentedUsersPersistence.json`):
```json
["40712345678", "4917675486961"]
```
Phone numbers are stored as digits-only strings. The file is read on startup to re-populate the in-memory `consentedUsers` Set.

---

### 5.3 Registration Flow

Triggered when `getSellerByPhone` returns `null` — the user has not registered on the web platform.

```
Bot DMs: "Visit <APP_BASE_URL> and click Sign Up. Reply 'registered' when done."
userState[phone].registrationPending = true

         ┌──────────────────────┬────────────────────────┐
         │                      │                        │
  User replies          Spring calls webhook      (timeout — state
  "registered"       /webhook/seller-registered   expires in 12h)
         │                      │
         └──────────┬───────────┘
                    ▼
             processListing()
```

The webhook path is the automated route — Spring triggers it immediately after successful registration. The `"registered"` DM reply is a manual fallback.

---

### 5.4 Media Processing

```
WhatsApp message (hasMedia = true)
         │
         ▼
msg.downloadMedia()
  → { data: '<base64>', mimetype: 'image/jpeg' }
         │
Stored in userState[phone].listing.imageUrls[]
         │
         │  At processListing() time:
         ▼
Construct Base64 data URI:
  `data:${mimetype};base64,${data}`
         │
         ▼
uploadImage(dataUri) → Cloudinary secure_url
         │
         ▼
Array of CDN URLs passed to createPost()
```

All images for a single user's session are uploaded in parallel via `Promise.all`.

---

### 5.5 QR Login Flow

```
Web app displays QR code containing token: "login:<uuid>"

User scans QR with phone camera ──► opens WhatsApp DM to bot

Bot receives DM matching /^login:([0-9a-f-]{36})$/i
         │
         ▼
confirmWhatsAppLogin(token, phoneNumber)
  → POST /api/auth/whatsapp/confirm
         │
         ├── 'OK'              → "Login successful! Go back to your browser."
         ├── 'EXPIRED'         → "This login link has expired."
         ├── 'PHONE_NOT_LINKED'→ "This number is not registered."
         ├── 'ALREADY_USED'    → "This login link was already used."
         └── null              → "Login failed due to a server error."
```

---

### 5.6 `processListing` — The Core Helper

```js
async function processListing(contact, currentUserListing)
```

Orchestrates the full upload pipeline:
1. Upload all images to Cloudinary (`Promise.all`).
2. Call `GeminiMessageParser` to extract structured fields.
3. Call `getSellerByPhone` to retrieve the `sellerId`.
4. Call `createPost` with the parsed data, CDN URLs, and seller ID.

**Return values:**
| Value | Meaning |
|---|---|
| `true` | Listing created successfully |
| `false` | Gemini parse failed or `createPost` failed |
| `null` | Seller not found — user needs to register |

---

## 6. Configuration & Environment

Copy `.env.example` to `.env` and fill in all values before starting the bot.

```bash
cp .env.example .env
```

| Variable | Required | Default | Description |
|---|---|---|---|
| `GEMINI_API_KEY` | Yes | — | Google AI Studio API key. Get one at [aistudio.google.com](https://aistudio.google.com). |
| `SPRING_BASE_URL` | Yes | `http://localhost:8080` | Base URL of the Spring Boot backend. No trailing slash. |
| `BOT_API_KEY` | Yes | — | Shared secret between the bot and Spring. Must match the value configured in the Spring app. Used in `X-Bot-Api-Key` headers. |
| `BOT_WEBHOOK_PORT` | No | `3001` | Port the Express webhook server listens on. |
| `TARGET_GROUP_JID` | No | `120363406751456779@g.us` | WhatsApp group JID monitored for seller listing capture. |
| `CLOUDINARY_CLOUD_NAME` | Yes | — | Your Cloudinary cloud name (from the dashboard). |
| `CLOUDINARY_API_KEY` | Yes | — | Cloudinary API key. |
| `CLOUDINARY_API_SECRET` | Yes | — | Cloudinary API secret. |
| `APP_BASE_URL` | No | `http://localhost:8080` | URL shown to unregistered users in DMs (e.g., `https://yourdomain.com`). |
| `LLM_PRIMARY_PROVIDER` | No | `openai-compatible` | Primary runtime provider (`gemini` or `openai-compatible`). |
| `LLM_FALLBACK_PROVIDERS` | No | empty | Comma-separated provider fallback chain. |
| `GEMINI_FALLBACK_MODELS` | No | internal defaults | Comma-separated Gemini model fallback list. |
| `OPENAI_BASE_URL` | No | `https://api.openai.com/v1` | Base URL for the OpenAI-compatible provider. |
| `OPENAI_API_KEY` | Yes* | — | API key for the OpenAI API. Required when using `openai-compatible`. |
| `OPENAI_MODEL` | No | `gpt-5.4-nano` | Model name passed to the OpenAI chat completions API. |

### Target Group

The bot monitors exactly one WhatsApp group, identified by its JID:

```js
// src/WhatsappBot.js:24
const TARGET_GROUP = process.env.TARGET_GROUP_JID || '120363406751456779@g.us'
```

To change the target group:
1. Start the bot and send a message in the desired group.
2. Check the console output — each message logs `from: <group-jid>`.
3. Copy that JID into `TARGET_GROUP_JID` in your local `.env`.

---

## 7. Developer Guide

### Starting the Bot

```bash
cd bot
npm install
node src/WhatsappBot.js
```

### Authentication (First Run)

On the first run (or after a session expires), a QR code is rendered in the terminal:

```
█████████████████████
█ ▄▄▄▄▄ █▀ █▀▀▀█ ▄▄▄▄▄ █
...
```

1. Open WhatsApp on your phone.
2. Go to **Settings → Linked Devices → Link a Device**.
3. Scan the QR code.
4. The terminal will print `Bot is ready!`.

The session is saved to `.wwebjs_auth/` and `.wwebjs_cache/`. The bot will reconnect automatically on subsequent starts without needing to re-scan.

### Running Tests

The test harness in `src/tests/GeminiAPI-test-Mock.js` exercises the full listing flow (classification → parsing → mock post creation) with real Gemini calls but mocked Cloudinary and Spring services.

```bash
# Requires GEMINI_API_KEY to be set in .env
node src/tests/GeminiAPI-test-Mock.js
```

The timer in the test harness is set to 2 seconds for fast iteration.

### Adjusting the Inactivity Timer

The listing detection timer defaults to 30 seconds in development. Before deploying to production, change it to 5 minutes:

```js
// src/WhatsappBot.js:141
}, 30000)   //TODO: change back to 300000 (5 min) for production
//   ↑ change to 300000
```

### Running the Benchmark (optional — requires Ollama)

The benchmark compares Gemini and local Gemma4 head-to-head on the same test cases and logs everything to Langfuse.

> Teammates without Ollama installed can skip this entirely — it has no effect on the bot.

**Prerequisites:**
```bash
ollama pull gemma4:e4b
```

**Run:**
```bash
node bot/src/tests/runBenchmark.js
```

Each run appends results to `bot/src/benchmark/benchmark-history.json` with:
- Per-test scores (valid JSON, correct fields, correct price, correct category)
- Per-model latency
- Per-model reliability score (`1 − errors/tests`)
- Full trace links in Langfuse

---

### Troubleshooting

**Bot shows QR code on every restart**

The `LocalAuth` session files are missing or corrupted. Delete `.wwebjs_auth/` and re-scan.

```bash
rm -rf .wwebjs_auth/
```

**`CRITICAL ERROR: GEMINI_API_KEY is not set`**

The `.env` file is missing or the key is empty. Verify the file exists in the `bot/` directory (not in `src/`). `dotenv` is configured to look two levels up from service files:
```js
require('dotenv').config({ path: path.resolve(__dirname, '../../.env') })
```

**Bot doesn't respond in the group**

- Verify `TARGET_GROUP` matches the actual group JID. Check console logs for the `from:` value of incoming messages.
- Ensure the WhatsApp account linked to the bot is a member of the group.

**Listing classified as 'NO' for valid messages**

The classification prompt uses examples to guide Gemini. If edge cases fail:
1. Add representative examples to the `classificationPrompt` in `Prompt-File.js`.
2. Run `GeminiAPI-test-Mock.js` with the failing message text to iterate quickly.

**`createPost` fails with 401**

The `BOT_API_KEY` in `.env` does not match the value configured in the Spring Boot application. They must be identical.

**Webhook endpoint returns 404**

The user's `registrationPending` flag is not set, meaning the bot never detected a listing or the user state expired (12-hour TTL). The user needs to re-send their listing in the group.

**Session crashes / Puppeteer errors**

`whatsapp-web.js` is sensitive to Chromium version mismatches. If Puppeteer fails to launch:
```bash
# Delete cached Chromium and reinstall
rm -rf node_modules/.cache
npm install
```
On headless Linux servers, ensure all Chromium dependencies are installed (see the `whatsapp-web.js` documentation for the required `apt` packages).
