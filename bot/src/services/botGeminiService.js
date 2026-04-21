const { UserMessagePrompt, classificationPrompt } = require("./Prompt-File.js");
const path = require('path');
// This ensures .env is loaded from the bot folder regardless of where you run node
require('dotenv').config({ path: path.resolve(__dirname, '../../.env') });
const { GoogleGenerativeAI } = require("@google/generative-ai")

if (!process.env.GEMINI_API_KEY) {
    console.error('CRITICAL ERROR: GEMINI_API_KEY is not set in .env file');
}

const genAI = new GoogleGenerativeAI(process.env.GEMINI_API_KEY)

const primaryModelName = "gemini-2.5-flash";
const fallbackModelName = "gemini-2.5-flash-lite";
const primaryModel = genAI.getGenerativeModel({ model: primaryModelName });
const fallbackModel = genAI.getGenerativeModel({ model: fallbackModelName });

async function generateWithFallback(prompt) {
    try {
        console.log(`[Gemini] Trying ${primaryModelName}...`);
        const result = await primaryModel.generateContent(prompt);
        return result.response;
    } catch (error) {
        console.warn(`[Gemini] ${primaryModelName} failed: ${error.message}`);
        console.log(`[Gemini] Falling back to ${fallbackModelName}...`);
        const result = await fallbackModel.generateContent(prompt);
        return result.response;
    }
}

async function GeminiMessageParser(message) {
    try {
        const response = await generateWithFallback(UserMessagePrompt(message));
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
        const response = await generateWithFallback(classificationPrompt(messages));
        const decision = response.text().trim().toUpperCase();
        // Clean up any extra text Gemini might have added
        return decision.includes('YES') ? 'YES' : 'NO';
    } catch (error) {
        console.error('Gemini Classifier error:', error.message);
        return 'NO';
    }
}

module.exports = { GeminiMessageParser, GeminiContextClassifier }
