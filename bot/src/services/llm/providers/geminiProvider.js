const { GoogleGenerativeAI } = require("@google/generative-ai")
const { UserMessagePrompt, classificationPrompt } = require("../../Prompt-File.js")

const DEFAULT_MODELS = [
    "gemini-2.5-flash-lite",
    "gemini-2.0-flash-lite",
    "gemini-1.5-flash-8b",
    "gemini-1.5-flash"
]

function parseModelList(raw) {
    if (!raw) return DEFAULT_MODELS
    return raw.split(",").map(item => item.trim()).filter(Boolean)
}

function createObservation(langfuse, tracingParams, name, model, input) {
    const { sessionId, userId, parent } = tracingParams || {}

    if (parent) {
        return parent.generation({
            name,
            model,
            input
        })
    }

    const trace = langfuse.trace({
        name,
        sessionId,
        userId
    })

    return trace.generation({
        name,
        model,
        input
    })
}

function createGeminiProvider({ langfuse }) {
    const apiKey = process.env.GEMINI_API_KEY
    const fallbackModels = parseModelList(process.env.GEMINI_FALLBACK_MODELS)
    const client = apiKey ? new GoogleGenerativeAI(apiKey) : null

    async function generate(prompt, tracingParams = {}, operationName = "GeminiGeneration") {
        if (!client) {
            throw new Error("GEMINI_API_KEY is not configured")
        }

        let lastError

        for (const modelName of fallbackModels) {
            const observation = createObservation(
                langfuse,
                tracingParams,
                operationName,
                modelName,
                tracingParams?.input || prompt
            )
            const startTime = new Date()

            try {
                const model = client.getGenerativeModel({ model: modelName })
                const result = await model.generateContent(prompt)
                const response = result.response

                observation.end({
                    output: response.text(),
                    startTime,
                    endTime: new Date(),
                    usage: response.usageMetadata ? {
                        promptTokens: response.usageMetadata.promptTokenCount,
                        completionTokens: response.usageMetadata.candidatesTokenCount,
                        totalTokens: response.usageMetadata.totalTokenCount
                    } : undefined
                })

                return response.text()
            } catch (error) {
                lastError = error
                observation.end({
                    level: "ERROR",
                    statusMessage: error.message,
                    startTime,
                    endTime: new Date()
                })
                continue
            }
        }

        throw lastError || new Error("Gemini failed with all fallback models")
    }

    async function parseListing(message, tracingParams = {}) {
        const prompt = UserMessagePrompt(message)
        const text = await generate(prompt, { ...tracingParams, input: message }, "GeminiMessageParser")
        const jsonMatch = text.match(/\{[\s\S]*\}/)
        const jsonString = jsonMatch ? jsonMatch[0] : text
        return JSON.parse(jsonString)
    }

    async function classifyContext(messages, tracingParams = {}) {
        const prompt = classificationPrompt(messages)
        const text = await generate(prompt, { ...tracingParams, input: messages }, "GeminiContextClassifier")
        const decision = text.trim().toUpperCase()
        return decision.includes("YES") ? "YES" : "NO"
    }

    return {
        name: "gemini",
        parseListing,
        classifyContext
    }
}

module.exports = { createGeminiProvider }
