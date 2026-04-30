const path = require("path")
require("dotenv").config({ path: path.resolve(__dirname, "../../../.env") })

const langfuse = require("../langfuseService")
const { createGeminiProvider } = require("./providers/geminiProvider")
const { createOpenAICompatibleProvider } = require("./providers/openaiCompatibleProvider")

const providers = {
    gemini: createGeminiProvider({ langfuse }),
    "openai-compatible": createOpenAICompatibleProvider({ langfuse })
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

function getProviderSequence() {
    const primary = normalizeProviderName(process.env.LLM_PRIMARY_PROVIDER || "gemini")
    const fallbacks = (process.env.LLM_FALLBACK_PROVIDERS || "")
        .split(",")
        .map(normalizeProviderName)
        .filter(Boolean)

    const sequence = uniqueNonEmpty([primary, ...fallbacks]).filter(name => providers[name])
    if (sequence.length === 0) return ["gemini"]
    return sequence
}

async function runWithFallback(operationName, executor) {
    const sequence = getProviderSequence()
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

async function MessageParser(message, tracingParams = {}) {
    try {
        return await runWithFallback("MessageParser", provider => provider.parseListing(message, tracingParams))
    } catch (error) {
        console.error("LLM Message Parser error:", error.message)
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
        fallbacks: (process.env.LLM_FALLBACK_PROVIDERS || "")
            .split(",")
            .map(normalizeProviderName)
            .filter(Boolean)
    }
}

module.exports = {
    MessageParser,
    ContextClassifier,
    getActiveProviderConfig
}
