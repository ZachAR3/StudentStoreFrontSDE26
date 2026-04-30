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

    function buildContent(prompt, images = []) {
        const content = [{ type: "text", text: prompt }]

        images.forEach(image => {
            if (!image?.data || !image?.mimetype) return
            content.push({
                type: "image_url",
                image_url: {
                    url: `data:${image.mimetype};base64,${image.data}`
                }
            })
        })

        return content
    }

    async function complete(prompt, images = [], tracingParams = {}, operationName = "OpenAICompatibleCompletion") {
        if (!client) {
            throw new Error("LITELLM_API_KEY is not configured")
        }

        const observation = createObservation(
            langfuse,
            tracingParams,
            operationName,
            model,
            tracingParams?.input || { prompt, imageCount: images.length }
        )
        const startTime = new Date()

        try {
            const response = await client.chat.completions.create({
                model,
                messages: [{ role: "user", content: buildContent(prompt, images) }],
                temperature: 0,
                max_tokens: Number(process.env.LISTING_PARSE_MAX_TOKENS || 180)
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

    async function parseListing(message, images = [], tracingParams = {}) {
        const prompt = UserMessagePrompt(message, images.length)
        const text = await complete(
            prompt,
            images,
            { ...tracingParams, input: { message, imageCount: images.length } },
            "OpenAICompatibleMessageParser"
        )
        const jsonMatch = text.match(/\{[\s\S]*\}/)
        const jsonString = jsonMatch ? jsonMatch[0] : text
        return JSON.parse(jsonString)
    }

    async function classifyContext(messages, tracingParams = {}) {
        const prompt = classificationPrompt(messages)
        const text = await complete(prompt, [], { ...tracingParams, input: messages }, "OpenAICompatibleContextClassifier")
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
