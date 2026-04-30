const { GoogleGenerativeAI } = require("@google/generative-ai")
const { UserMessagePrompt, classificationPrompt, imageDescriptionPrompt } = require("../../Prompt-File.js")

const DEFAULT_MODELS = [
    "gemini-2.5-flash-lite",
    "gemini-2.5-flash"
]
const DEPRECATED_MODEL_REPLACEMENTS = {
    "gemini-1.5-flash": "gemini-2.5-flash-lite",
    "gemini-1.5-flash-8b": "gemini-2.5-flash-lite",
    "gemini-1.5-pro": "gemini-2.5-flash",
    "gemini-pro-vision": "gemini-2.5-flash-lite"
}

function parseModelList(raw) {
    if (!raw) return DEFAULT_MODELS
    const models = raw
        .split(",")
        .map(item => item.trim())
        .filter(Boolean)
        .map(modelName => DEPRECATED_MODEL_REPLACEMENTS[modelName] || modelName)

    return [...new Set(models.length ? models : DEFAULT_MODELS)]
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

    function buildParts(prompt, images = []) {
        const parts = [{ text: prompt }]

        images.forEach(image => {
            if (!image?.data || !image?.mimetype) return
            parts.push({
                inlineData: {
                    data: image.data,
                    mimeType: image.mimetype
                }
            })
        })

        return parts
    }

    async function generate(prompt, images = [], tracingParams = {}, operationName = "GeminiGeneration") {
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
                tracingParams?.input || { prompt, imageCount: images.length }
            )
            const startTime = new Date()

            try {
                const model = client.getGenerativeModel({
                    model: modelName,
                    generationConfig: {
                        temperature: 0,
                        maxOutputTokens: Number(process.env.LISTING_PARSE_MAX_TOKENS || 180)
                    }
                })
                const result = await model.generateContent(buildParts(prompt, images))
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

    async function parseListing(message, images = [], tracingParams = {}) {
        const prompt = UserMessagePrompt(message, images.length)
        const text = await generate(
            prompt,
            images,
            { ...tracingParams, input: { message, imageCount: images.length } },
            "GeminiMessageParser"
        )
        const jsonMatch = text.match(/\{[\s\S]*\}/)
        const jsonString = jsonMatch ? jsonMatch[0] : text
        return JSON.parse(jsonString)
    }

    async function imageProcessing(images, tracingParams = {}) {
        if (!client) {
            throw new Error("GEMINI_API_KEY is not configured")
        }

        const parts = [
            ...images.map(img => ({
                inlineData: { data: img.data, mimeType: img.mimetype }
            })),
            { text: imageDescriptionPrompt() }
        ]

        let lastError
        for (const modelName of fallbackModels) {
            const observation = createObservation(
                langfuse,
                tracingParams,
                "GeminiImageDescriber",
                modelName,
                `${images.length} image(s)`
            )
            const startTime = new Date()

            try {
                const model = client.getGenerativeModel({ model: modelName })
                const result = await model.generateContent(parts)
                const response = result.response
                const text = response.text()

                observation.end({
                    output: text,
                    startTime,
                    endTime: new Date(),
                    usage: response.usageMetadata ? {
                        promptTokens: response.usageMetadata.promptTokenCount,
                        completionTokens: response.usageMetadata.candidatesTokenCount,
                        totalTokens: response.usageMetadata.totalTokenCount
                    } : undefined
                })

                return text
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

        throw lastError || new Error("Gemini image processing failed with all fallback models")
    }

    async function classifyContext(messages, tracingParams = {}) {
        const prompt = classificationPrompt(messages)
        const text = await generate(prompt, [], { ...tracingParams, input: messages }, "GeminiContextClassifier")
        const decision = text.trim().toUpperCase()
        return decision.includes("YES") ? "YES" : "NO"
    }

    return {
        name: "gemini",
        isConfigured: !!client,
        parseListing,
        classifyContext,
        imageProcessing
    }
}

module.exports = { createGeminiProvider }
