const { UserMessagePrompt, classificationPrompt } = require("../../services/Prompt-File.js");
const path = require('path');
// This ensures .env is loaded from the bot folder regardless of where you run node
require('dotenv').config({ path: path.resolve(__dirname, '../../../.env') });
const { GoogleGenerativeAI } = require("@google/generative-ai");
const langfuse = require("../../services/langfuseService");

if (!process.env.GEMINI_API_KEY) {
    console.error('CRITICAL ERROR: GEMINI_API_KEY is not set in .env file');
}

const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY)

// Models tried in order; on 403/quota/rate-limit the next one is used
const FALLBACK_MODELS = [
    "gemini-2.5-flash-lite",
    "gemini-2.0-flash-lite",
    "gemini-1.5-flash-8b",
    "gemini-1.5-flash",
];


async function generateContent(prompt, tracingParams = {}) {
    const { name, sessionId, userId, input, parent } = tracingParams;

    let lastError;

    for (const modelName of FALLBACK_MODELS) {
        const model = genAI.getGenerativeModel({ model: modelName });

        // Each attempt gets its own generation span so failures are visible in Langfuse
        let observation;
        if (parent) {
            observation = parent.generation({
                name: name || "gemini-generation",
                model: modelName,
                input: input || prompt
            });
        } else {
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
        }

        // Track latency of the Gemini API call
        const startTime = new Date();
        try {
            console.log(`[Gemini] Calling ${modelName}...`);
            const result = await model.generateContent(prompt);
            const endTime = new Date();
            const response = result.response;

            const text = response.text();

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

            return response;
        } catch (error) {
            lastError = error;
            observation.end({
                level: "ERROR",
                statusMessage: error.message,
                startTime,
                endTime: new Date()
            });

            const nextModel = FALLBACK_MODELS[FALLBACK_MODELS.indexOf(modelName) + 1];
            if (nextModel) {
                console.warn(`[Gemini] ${modelName} failed: ${error.message} — ${nextModel} is taking over`);
            } else {
                console.error(`[Gemini] ${modelName} failed: ${error.message}`);
            }
            continue;
        }
    }

    console.error('[Gemini] All fallback models exhausted');
    throw lastError;
}

async function GeminiMessageParser(message, tracingParams = {}) {
    try {
        const prompt = UserMessagePrompt(message);
        const response = await generateContent(prompt, {
            name: "GeminiMessageParser",
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
        console.error('Gemini Message Parser error:', error.message);
        return null;
    }
}

async function GeminiContextClassifier(messages, tracingParams = {}) {
    try {
        const prompt = classificationPrompt(messages);
        const response = await generateContent(prompt, {
            name: "GeminiContextClassifier",
            sessionId: tracingParams.sessionId,
            userId: tracingParams.userId,
            input: messages,
            parent: tracingParams.parent
        });
        const decision = response.text().trim().toUpperCase();
        // Clean up any extra text Gemini might have added
        return decision.includes('YES') ? 'YES' : 'NO';
    } catch (error) {
        console.error('Gemini Classifier error:', error.message);
        return 'NO';
    }
}

module.exports = { GeminiMessageParser, GeminiContextClassifier }
