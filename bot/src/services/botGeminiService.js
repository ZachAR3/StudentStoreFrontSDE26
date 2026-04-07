const {textPrompt} = require( "./Prompt-File.js");
require('dotenv').config()
const {GoogleGenAI} = require("@google/genai")
const ai = new GoogleGenAI({})
async function GeminiMessageParser (message) {
    const response = await ai.models.generateContent({
        model: "gemini-2.0-flash",
        contents: textPrompt(message)
    });
    console.log(response.text)
}

module.exports = {GeminiMessageParser}