const path = require('path');
require('dotenv').config({ path: path.resolve(__dirname, '../../../.env') });

const langfuse = require("../../services/langfuseService");
const { createOpenAICompatibleProvider } = require("../../services/llm/providers/openaiCompatibleProvider.js");

const provider = createOpenAICompatibleProvider({ langfuse });

async function GemmaMessageParser(message, tracingParams = {}) {
    return provider.parseListing(message, [], tracingParams);
}

async function GemmaContextClassifier(messages, tracingParams = {}) {
    return provider.classifyContext(messages, tracingParams);
}

module.exports = { GemmaMessageParser, GemmaContextClassifier }
