const { UserMessagePrompt, classificationPrompt } = require("./Prompt-File.js");
const path = require('path');
// This ensures .env is loaded from the bot folder regardless of where you run node
require('dotenv').config({ path: path.resolve(__dirname, '../../.env') });
const { GoogleGenerativeAI } = require("@google/generative-ai")

if (!process.env.GEMINI_API_KEY) {
    console.error('CRITICAL ERROR: GEMINI_API_KEY is not set in .env file');
}

const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY)

const modelName = "gemini-2.5-flash-lite";
const model = genAI.getGenerativeModel({ model: modelName });

async function generateContent(prompt) {
    try {
        console.log(`[Gemini] Calling ${modelName}...`);
        const result = await model.generateContent(prompt);
        return result.response;
    } catch (error) {
        console.error(`[Gemini] ${modelName} failed: ${error.message}`);
        throw error;
    }
}

async function GeminiMessageParser(message) {
    try {
        const response = await generateContent(UserMessagePrompt(message));
        const text = response.text();

        const jsonMatch = text.match(/\{[\s\S]*\}/);
        const jsonString = jsonMatch ? jsonMatch[0] : text;

        return JSON.parse(jsonString);
    } catch (error) {
        console.error('Gemini Message Parser error:', error.message);
        return null;
    }
}

async function GeminiContextClassifier(messages) {
    try {
        const response = await generateContent(classificationPrompt(messages));
        const decision = response.text().trim().toUpperCase();
        // Clean up any extra text Gemini might have added
        return decision.includes('YES') ? 'YES' : 'NO';
    } catch (error) {
        console.error('Gemini Classifier error:', error.message);
        return 'NO';
    }
}

module.exports = { GeminiMessageParser, GeminiContextClassifier }
