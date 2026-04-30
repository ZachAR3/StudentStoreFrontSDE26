const OpenAIImport = require("openai")
const { UserMessagePrompt, classificationPrompt, imageDescriptionPrompt } = require("../../Prompt-File.js")

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

const _UNSET = Symbol("unset")

function createOpenAICompatibleProvider({ langfuse, baseURL: baseURLOverride, apiKey: apiKeyOverride = _UNSET, model: modelOverride } = {}) {
    const baseURL = baseURLOverride || process.env.LITELLM_BASE_URL || DEFAULT_BASE_URL
    const apiKey = apiKeyOverride !== _UNSET ? apiKeyOverride : process.env.LITELLM_API_KEY
    const model = modelOverride || process.env.LITELLM_MODEL || DEFAULT_MODEL

    const client = apiKey ? new OpenAI({
        baseURL,
        apiKey
    }) : null

    async function complete(prompt, tracingParams = {}, operationName = "OpenAICompatibleCompletion") {
        if (!client) {
            throw new Error(`API key is not configured for provider at ${baseURL}`)
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

    async function imageProcessing(images, tracingParams = {}) {
        if (!client) {
            throw new Error(`API key is not configured for provider at ${baseURL}`)
        }

        const content = [
            ...images.map(img => ({
                type: "image_url",
                image_url: { url: `data:${img.mimetype};base64,${img.data}` }
            })),
            { type: "text", text: imageDescriptionPrompt() }
        ]

        const observation = createObservation(
            langfuse,
            tracingParams,
            "OpenAICompatibleImageDescriber",
            model,
            `${images.length} image(s)`
        )
        const startTime = new Date()

        try {
            const response = await client.chat.completions.create({
                model,
                messages: [{ role: "user", content }]
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

    async function classifyContext(messages, tracingParams = {}) {
        const prompt = classificationPrompt(messages)
        const text = await complete(prompt, { ...tracingParams, input: messages }, "OpenAICompatibleContextClassifier")
        const decision = text.trim().toUpperCase()
        return decision.includes("YES") ? "YES" : "NO"
    }

    return {
        name: "openai-compatible",
        isConfigured: !!client,
        parseListing,
        imageProcessing,
        classifyContext
    }

}


module.exports = { createOpenAICompatibleProvider }
