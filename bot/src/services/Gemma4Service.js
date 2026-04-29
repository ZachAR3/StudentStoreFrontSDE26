const http = require('http');
const { UserMessagePrompt, classificationPrompt } = require("./Prompt-File.js");
const path = require('path');
// This ensures .env is loaded from the bot folder regardless of where you run node
require('dotenv').config({ path: path.resolve(__dirname, '../../.env') });
const langfuse = require("./langfuseService");

const OLLAMA_HOST = process.env.OLLAMA_HOST || '127.0.0.1';
const OLLAMA_PORT = parseInt(process.env.OLLAMA_PORT || '11434', 10);
const modelName   = process.env.OLLAMA_MODEL || 'gemma4:e4b';

// ─── Native HTTP call to Ollama /api/generate ────────────────────────────────
function callOllama(prompt) {
    const payload = JSON.stringify({
        model: modelName,
        prompt: prompt,
        stream: false
    });

    const options = {
        hostname: OLLAMA_HOST,
        port: OLLAMA_PORT,
        path: '/api/generate',
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'Content-Length': Buffer.byteLength(payload)
        }
    };

    return new Promise((resolve, reject) => {
        const req = http.request(options, (res) => {
            let data = '';
            res.on('data', chunk => { data += chunk; });
            res.on('end', () => {
                if (res.statusCode < 200 || res.statusCode >= 300) {
                    return reject(new Error(`Ollama HTTP ${res.statusCode}: ${data}`));
                }
                try {
                    const parsed = JSON.parse(data);
                    resolve(parsed.response || '');
                } catch (err) {
                    reject(new Error(`Ollama returned non-JSON response: ${err.message}`));
                }
            });
        });
        req.on('error', reject);
        req.write(payload);
        req.end();
    });
}

async function generateContent(prompt, tracingParams = {}) {
    const { name, sessionId, userId, input, parent } = tracingParams;

    // Trace this single Gemma generation; nests under parent if provided,
    // otherwise creates a standalone trace tied to the (sessionId, userId) pair.
    let observation;
    if (parent) {
        observation = parent.generation({
            name: name || "gemma-generation",
            model: modelName,
            input: input || prompt
        });
    } else {
        const trace = langfuse.trace({
            name: name || "gemma-generation",
            sessionId: sessionId,
            userId: userId
        });
        observation = trace.generation({
            name: name || "gemma-generation",
            model: modelName,
            input: input || prompt
        });
    }

    // Track latency of the Ollama API call
    const startTime = new Date();
    try {
        console.log(`[Gemma] Calling ${modelName}...`);
        const text = await callOllama(prompt);
        const endTime = new Date();

        // Ollama /api/generate does not return OpenAI-style token usage, so we omit it
        observation.end({
            output: text,
            startTime,
            endTime
        });

        return { text: () => text };
    } catch (error) {
        console.error(`[Gemma] ${modelName} failed: ${error.message}`);
        observation.end({
            level: "ERROR",
            statusMessage: error.message,
            startTime,
            endTime: new Date()
        });
        throw error;
    }
}

async function GemmaMessageParser(message, tracingParams = {}) {
    try {
        const prompt = UserMessagePrompt(message);
        const response = await generateContent(prompt, {
            name: "GemmaMessageParser",
            sessionId: tracingParams.sessionId,
            userId: tracingParams.userId,
            input: message,
            parent: tracingParams.parent
        });
        const text = response.text();

        const jsonMatch = text.match(/\{[\s\S]*\}/);
        const jsonString = jsonMatch ? jsonMatch[0] : text;

        return JSON.parse(jsonString);
    } catch (error) {
        console.error('Gemma Message Parser error:', error.message);
        return null;
    }
}

async function GemmaContextClassifier(messages, tracingParams = {}) {
    try {
        const prompt = classificationPrompt(messages);
        const response = await generateContent(prompt, {
            name: "GemmaContextClassifier",
            sessionId: tracingParams.sessionId,
            userId: tracingParams.userId,
            input: messages,
            parent: tracingParams.parent
        });
        const decision = response.text().trim().toUpperCase();
        // Clean up any extra text Gemma might have added
        return decision.includes('YES') ? 'YES' : 'NO';
    } catch (error) {
        console.error('Gemma Classifier error:', error.message);
        return 'NO';
    }
}

module.exports = { GemmaMessageParser, GemmaContextClassifier }
