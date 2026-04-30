const path = require("path")
require("dotenv").config({ path: path.resolve(__dirname, "../../../.env") })

const langfuse = require("../langfuseService")
const { createGeminiProvider } = require("./providers/geminiProvider")
const { createOpenAICompatibleProvider } = require("./providers/openaiCompatibleProvider")

const providers = {
    gemini: createGeminiProvider({ langfuse }),
    "openai-compatible": createOpenAICompatibleProvider({ langfuse }),
    gemma: createOpenAICompatibleProvider({
        langfuse,
        baseURL: process.env.LITELLM_BASE_URL,
        apiKey: process.env.LITELLM_API_KEY,
        model: process.env.LITELLM_MODEL
    }),
    chatgpt: createOpenAICompatibleProvider({
        langfuse,
        baseURL: "https://api.openai.com/v1",
        apiKey: process.env.OPENAI_API_KEY,
        model: "gpt-4o-mini"
    })
}

function uniqueNonEmpty(values) {
    const seen = new Set()
    return values.filter(value => {
        if (!value || seen.has(value)) return false
        seen.add(value)
        return true
    })
}

function normalizeProviderName(name) {
    return (name || "").trim().toLowerCase()
}

function parseProviderList(raw) {
    return (raw || "")
        .split(",")
        .map(normalizeProviderName)
        .filter(Boolean)
}

function getProviderSequence() {
    const primary = normalizeProviderName(process.env.LLM_PRIMARY_PROVIDER || "gemini")
    const fallbacks = parseProviderList(process.env.LLM_FALLBACK_PROVIDERS)

    const sequence = uniqueNonEmpty([primary, ...fallbacks]).filter(name => providers[name])
    if (sequence.length === 0) return ["gemini"]
    return sequence
}

function getParseSequence() {
    const configuredParseProviders = parseProviderList(process.env.LLM_PARSE_PROVIDERS)
    if (configuredParseProviders.length === 0) return getProviderSequence()

    const sequence = uniqueNonEmpty(configuredParseProviders).filter(name => providers[name])
    return sequence.length ? sequence : getProviderSequence()
}

async function runWithFallback(operationName, executor, sequence = getProviderSequence()) {
    let lastError = null

    for (const providerName of sequence) {
        const provider = providers[providerName]
        try {
            return await executor(provider)
        } catch (error) {
            lastError = error
            console.warn(`[LLM] ${operationName} failed on provider "${providerName}": ${error.message}`)
        }
    }

    throw lastError || new Error(`[LLM] ${operationName} failed on all configured providers`)
}

async function MessageParser(message, images = [], tracingParams = {}) {
    try {
        return await runWithFallback("MessageParser", provider => provider.parseListing(message, images, tracingParams), getParseSequence())
    } catch (error) {
        console.error("LLM Message Parser error:", error.message)
        return null
    }
}

async function ImageDescriber(images, tracingParams = {}) {
    try {
        return await runWithFallback("ImageDescriber", provider => provider.imageProcessing(images, tracingParams), getParseSequence())
    } catch (error) {
        console.error("LLM Image Describer error:", error.message)
        return null
    }
}

async function ContextClassifier(messages, tracingParams = {}) {
    try {
        return await runWithFallback("ContextClassifier", provider => provider.classifyContext(messages, tracingParams))
    } catch (error) {
        console.error("LLM Classifier error:", error.message)
        return "NO"
    }
}

function getActiveProviderConfig() {
    return {
        primary: normalizeProviderName(process.env.LLM_PRIMARY_PROVIDER || "gemini"),
        fallbacks: parseProviderList(process.env.LLM_FALLBACK_PROVIDERS),
        parseProviders: parseProviderList(process.env.LLM_PARSE_PROVIDERS)
    }
}

module.exports = {
    MessageParser,
    ContextClassifier,
    ImageDescriber,
    getActiveProviderConfig
}
