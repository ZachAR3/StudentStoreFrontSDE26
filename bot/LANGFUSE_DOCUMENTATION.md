# Langfuse Tracing — StudentStoreFront Bot

## 1. Overview

Langfuse is used to observe every Gemini API call the bot makes, along with the
higher-level pipeline steps (image upload, post creation) that surround those
calls. Each user interaction that triggers a Gemini call is recorded as a named
trace with a user ID and session ID, so you can filter the Langfuse dashboard by
phone number and see the full history of what the bot did for a given user.
Latency, token usage, and the raw input/output of every generation are captured
automatically inside `generateContent()`.

---

## 2. `langfuseService.js` — the shared client

```js
const { Langfuse } = require("langfuse");

const langfuse = new Langfuse({
    publicKey: process.env.LANGFUSE_PUBLIC_KEY,
    secretKey: process.env.LANGFUSE_SECRET_KEY,
    baseUrl: process.env.LANGFUSE_BASEURL,
});

process.on("beforeExit", async () => {
    await langfuse.shutdownAsync();
});

module.exports = langfuse;
```

The file constructs exactly one `Langfuse` instance and exports it. Because
Node.js caches the result of `require()`, every file that calls
`require('./langfuseService')` receives the same object. This matters because
the Langfuse SDK buffers events in memory before sending them to the server in
batches. If each file created its own instance, they would maintain separate
buffers, making it possible for events to be lost or sent out of order. A shared
singleton keeps one buffer and one network connection.

The `process.on("beforeExit")` hook calls `langfuse.shutdownAsync()`, which
flushes the internal event buffer and closes the HTTP connection cleanly when
the Node process is about to exit naturally. This is a safety net; explicit
`langfuse.flush()` calls at each pipeline end-point are also present (see
Section 7) because the bot is a long-running process that rarely exits cleanly.

**Required environment variables**

| Variable | Purpose |
|---|---|
| `LANGFUSE_PUBLIC_KEY` | Identifies the project to the Langfuse API |
| `LANGFUSE_SECRET_KEY` | Authenticates write access |
| `LANGFUSE_BASEURL` | Points to the Langfuse server (cloud or self-hosted) |

---

## 3. Every Langfuse call in the codebase

### `WhatsappBot.js` — `processListing()`

**`langfuse.trace()`** — line 31
```js
const trace = langfuse.trace({
    name: "ProcessListing",
    sessionId: contact.number,
    userId: contact.number
});
```
Creates the root trace for the entire listing pipeline. `sessionId` and
`userId` are both set to the user's phone number so the trace appears under that
user in the dashboard and can be grouped with other traces for the same number.

**`trace.span()`** — line 38 (`CloudinaryUpload`)
```js
const cloudinarySpan = trace.span({ name: "CloudinaryUpload" });
// ... upload work ...
cloudinarySpan.end({ output: { count: cloudinaryUrls.length } });
```
Wraps the Cloudinary image upload block. The span records how many images were
uploaded successfully (`count`). It is placed here to isolate the external
Cloudinary latency from the Gemini latency.

**`GeminiMessageParser()` called with `parent: trace`** — line 48
```js
const parsedListing = await GeminiMessageParser(currentUserListing.messages.join('\n'), {
    sessionId: contact.number,
    userId: contact.number,
    parent: trace
});
```
Passes the root trace as `parent` so the generation created inside
`GeminiMessageParser` is attached to the `ProcessListing` trace rather than
creating a separate top-level trace. See Section 4 for how this works.

**`trace.end()` with ERROR level** — line 56
```js
trace.end({ level: "ERROR", statusMessage: "Gemini parsing failed" });
```
Closes the trace early and marks it as an error when Gemini returns a result
that cannot be parsed as JSON.

**`trace.end()` with WARNING level** — line 63
```js
trace.end({ level: "WARNING", statusMessage: "Seller not found" });
```
Closes the trace early with a warning when the seller lookup against the Spring
backend returns nothing. The listing data was valid; the user simply is not
registered.

**`trace.span()`** — line 69 (`CreatePost`)
```js
const springSpan = trace.span({ name: "CreatePost" });
const result = await createPost(parsedListing, cloudinaryUrls, seller.sellerId)
springSpan.end({ output: result ? "SUCCESS" : "FAILED" });
```
Wraps the HTTP call to the Spring backend that creates the post. The span output
records whether the backend accepted the post.

**`trace.end()` on success/failure** — line 73
```js
trace.end({ output: result ? "SUCCESS" : "FAILED" });
```
Closes the root trace and records the final outcome of the whole pipeline.

---

### `WhatsappBot.js` — classification timer inside `client.on('message')`

**`langfuse.trace()`** — line 118
```js
const classificationTrace = langfuse.trace({
    name: "ClassificationTimer",
    sessionId: contact.number,
    userId: contact.number
});
```
Creates a root trace for the deferred classification decision. It fires after
the user's message-collection window closes (the timer is reset on every new
group message). `sessionId` and `userId` are the phone number.

**`GeminiContextClassifier()` called with `parent: classificationTrace`** — line 124
```js
const geminiResponse = await GeminiContextClassifier(state.listing.messages, {
    sessionId: contact.number,
    userId: contact.number,
    parent: classificationTrace
});
```
Passes the classification trace as `parent` so the generation is nested under
it in the dashboard.

**`classificationTrace.update()`** — line 130
```js
classificationTrace.update({ output: geminiResponse });
```
Writes the YES/NO classification result back onto the root trace after
`GeminiContextClassifier` returns. This makes the decision visible at the trace
level in the dashboard without having to open the nested generation.

---

### `botGeminiService.js` — `generateContent()`

This is the only function that creates Langfuse `generation` observations. All
other tracing in the project flows through here.

**`parent.generation()` (when a parent is provided)** — line 22
```js
observation = parent.generation({
    name: name || "gemini-generation",
    model: modelName,
    input: input || prompt
});
```
Attaches the generation as a child of whichever trace was passed in from the
caller. `name` is the name of the calling function (`"GeminiMessageParser"` or
`"GeminiContextClassifier"`). `input` is the caller's pre-prompt value (the raw
message string or messages array), not the rendered prompt string, so the input
shown in Langfuse reflects what the user actually sent rather than the full
system prompt.

**`langfuse.trace()` + `trace.generation()` (when no parent is provided)** — lines 28–37
```js
const trace = langfuse.trace({
    name: name || "gemini-generation",
    sessionId: sessionId,
    userId: userId
});
observation = trace.generation({
    name: name || "gemini-generation",
    model: modelName,
    input: input || prompt
});
```
If `generateContent()` is called without a parent trace, it creates its own
top-level trace so the generation is still recorded. This is a fallback; in
practice, all current callers pass a parent.

**`observation.end()` on success** — line 50
```js
observation.end({
    output: text,
    startTime,
    endTime,
    usage: response.usageMetadata ? {
        promptTokens: response.usageMetadata.promptTokenCount,
        completionTokens: response.usageMetadata.candidatesTokenCount,
        totalTokens: response.usageMetadata.totalTokenCount
    } : undefined
});
```
Closes the generation and records all metrics. See Section 6 for the full field
breakdown.

**`observation.end()` on error** — line 64
```js
observation.end({
    level: "ERROR",
    statusMessage: error.message,
    startTime,
    endTime: new Date()
});
```
Closes the generation as an error. `startTime` is still passed so Langfuse can
compute how long the call ran before it failed.

---

## 4. The parent tracing pattern

Both `GeminiMessageParser` and `GeminiContextClassifier` accept an optional
`tracingParams` object. When the caller sets `tracingParams.parent` to a
Langfuse trace, it is forwarded into `generateContent()` as `tracingParams.parent`.

Inside `generateContent()`, the check is:

```js
if (parent) {
    observation = parent.generation({ ... });
} else {
    const trace = langfuse.trace({ ... });
    observation = trace.generation({ ... });
}
```

When `parent` is set, `parent.generation()` is called directly on the caller's
trace object. The Langfuse SDK records the new generation with a reference to
that trace's ID, so the dashboard renders it as a child node rather than a
separate top-level entry.

The result is that a single user action produces one trace with all its steps
nested inside it, rather than several disconnected traces that must be
cross-referenced manually.

---

## 5. Trace hierarchy diagrams

### `ProcessListing` trace

Triggered when a user's collected messages are submitted for listing creation.

```
ProcessListing  [trace]
│   sessionId: <phone number>
│   userId:    <phone number>
│
├── CloudinaryUpload  [span]
│       output: { count: <n> }
│
├── GeminiMessageParser  [generation]
│       model:     gemini-2.5-flash-lite
│       input:     joined message string (raw user text)
│       output:    raw Gemini response text
│       startTime: <Date before API call>
│       endTime:   <Date after API call>
│       usage:     { promptTokens, completionTokens, totalTokens }
│
└── CreatePost  [span]
        output: "SUCCESS" | "FAILED"
```

If the pipeline exits early, the trace ends at the point of failure:

```
ProcessListing  [trace, level: ERROR]
│
├── CloudinaryUpload  [span]
└── GeminiMessageParser  [generation, level: ERROR]

— or —

ProcessListing  [trace, level: WARNING, statusMessage: "Seller not found"]
│
├── CloudinaryUpload  [span]
└── GeminiMessageParser  [generation]
```

---

### `ClassificationTimer` trace

Triggered after the message-collection window closes for a group message.

```
ClassificationTimer  [trace]
│   sessionId: <phone number>
│   userId:    <phone number>
│   output:    "YES" | "NO"   (set via trace.update() after the call)
│
└── GeminiContextClassifier  [generation]
        model:     gemini-2.5-flash-lite
        input:     messages array (raw user text array)
        output:    raw Gemini response text
        startTime: <Date before API call>
        endTime:   <Date after API call>
        usage:     { promptTokens, completionTokens, totalTokens }
```

---

## 6. What is recorded for each generation

Every call to `generateContent()` records the following fields on the generation
observation.

| Field | Source | Notes |
|---|---|---|
| `name` | caller's `tracingParams.name` | `"GeminiMessageParser"` or `"GeminiContextClassifier"` |
| `model` | `modelName` constant | `"gemini-2.5-flash-lite"` |
| `input` | caller's `tracingParams.input` | Raw pre-prompt value; falls back to the rendered `prompt` string if not set |
| `output` | `response.text()` | Full raw text returned by the Gemini API |
| `startTime` | `new Date()` before `model.generateContent()` | Wall-clock time the HTTP request was sent |
| `endTime` | `new Date()` after `model.generateContent()` resolves | Wall-clock time the response was received |
| `usage.promptTokens` | `response.usageMetadata.promptTokenCount` | Only present if the API returns `usageMetadata` |
| `usage.completionTokens` | `response.usageMetadata.candidatesTokenCount` | Only present if the API returns `usageMetadata` |
| `usage.totalTokens` | `response.usageMetadata.totalTokenCount` | Only present if the API returns `usageMetadata` |

On error, `output`, `endTime` (set to `new Date()` at catch time), `level: "ERROR"`,
and `statusMessage: error.message` are recorded instead of the token fields.

Langfuse derives the generation latency from `endTime - startTime`.

---

## 7. When and why `langfuse.flush()` is called

The Langfuse SDK queues events and sends them to the server asynchronously in
batches. In a short-lived script, `shutdownAsync()` drains the queue on exit. The
bot is a long-running process that receives messages continuously and rarely
exits, so `shutdownAsync()` would not fire for hours or days. To prevent data
loss, `langfuse.flush()` is called explicitly at every natural end-of-pipeline
point — immediately after a trace is closed.

There are four call sites:

| Location | Condition |
|---|---|
| `processListing()` line 57 | Gemini parse failed — trace ends with ERROR |
| `processListing()` line 64 | Seller not found — trace ends with WARNING |
| `processListing()` line 74 | Pipeline completed (success or Spring failure) |
| Classification timer line 131 | Classification decision returned |

Each `flush()` call is `await`-ed so the calling code does not proceed until the
SDK has delivered the buffered events.
