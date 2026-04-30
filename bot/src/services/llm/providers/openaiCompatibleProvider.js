const OpenAIImport = require("openai")
const { UserMessagePrompt, classificationPrompt } = require("../../Prompt-File.js")

const OpenAI = OpenAIImport.default || OpenAIImport
const DEFAULT_BASE_URL = "https://ai.zachar3.duckdns.org/v1"
const DEFAULT_MODEL = "ollama/gemma4:e4b"

function extractText(content) {
    if (typeof content === "string") return content
    if (!Array.isArray(content)) return ""

    return content
        .map(part => {
            if (!part || typeof part !== "object") return ""
            if (part.type === "text") return part.text || ""
            if (part.type === "output_text") return part.text || ""
            return ""
        })
        .join("")
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

function createOpenAICompatibleProvider({ langfuse }) {
    const baseURL = process.env.LITELLM_BASE_URL || DEFAULT_BASE_URL
    const apiKey = process.env.LITELLM_API_KEY
    const model = process.env.LITELLM_MODEL || DEFAULT_MODEL

    const client = apiKey ? new OpenAI({
        baseURL,
        apiKey
    }) : null

    async function complete(prompt, tracingParams = {}, operationName = "OpenAICompatibleCompletion") {
        if (!client) {
            throw new Error("LITELLM_API_KEY is not configured")
        }

        const observation = createObservation(
            langfuse,
            tracingParams,
            operationName,
            model,
            tracingParams?.input || prompt
        )
        const startTime = new Date()

        try {
            const response = await client.chat.completions.create({
                model,
                messages: [{ role: "user", content: prompt }]
            })

            const text = extractText(response?.choices?.[0]?.message?.content)

            observation.end({
                output: text,
                startTime,
                endTime: new Date(),
                usage: response?.usage ? {
                    promptTokens: response.usage.prompt_tokens,
                    completionTokens: response.usage.completion_tokens,
                    totalTokens: response.usage.total_tokens
                } : undefined
            })

            return text
        } catch (error) {
            observation.end({
                level: "ERROR",
                statusMessage: error.message,
                startTime,
                endTime: new Date()
            })
            throw error
        }
    }

    async function parseListing(message, tracingParams = {}) {
        const prompt = UserMessagePrompt(message)
        const text = await complete(prompt, { ...tracingParams, input: message }, "OpenAICompatibleMessageParser")
        const jsonMatch = text.match(/\{[\s\S]*\}/)
        const jsonString = jsonMatch ? jsonMatch[0] : text
        return JSON.parse(jsonString)
    }

    async function classifyContext(messages, tracingParams = {}) {
        const prompt = classificationPrompt(messages)
        const text = await complete(prompt, { ...tracingParams, input: messages }, "OpenAICompatibleContextClassifier")
        const decision = text.trim().toUpperCase()
        return decision.includes("YES") ? "YES" : "NO"
    }

    return {
        name: "openai-compatible",
        parseListing,
        classifyContext
    }
}

module.exports = { createOpenAICompatibleProvider }
