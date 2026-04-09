const {textPrompt} = require( "./Prompt-File.js");
require('dotenv').config()
const {GoogleGenAI} = require("@google/genai")
const {classificationPrompt} = require("./Prompt-File");
const ai = new GoogleGenAI({})
async function GeminiMessageParser (message) {
    const response = await ai.models.generateContent({
        model: "gemini-2.0-flash-lite",
        contents: textPrompt(message)
    });
    console.log(response.text)
    return response.text
}

async function GeminiContextClassifier(messages) {
    const response = await ai.models.generateContent({
        model: "gemini-2.0-flash-lite",
        contents: classificationPrompt(messages)
    });
    return response.text
}

module.exports = {GeminiMessageParser, GeminiContextClassifier}