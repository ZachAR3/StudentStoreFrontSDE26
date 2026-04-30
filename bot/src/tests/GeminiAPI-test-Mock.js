const { GeminiMessageParser, GeminiContextClassifier } = require('./LLM_Services_For_Testing/botGeminiService.js');
const fs = require('fs');

// --- Mocks for Services ---
const mockUploadImage = async (base64) => {
    console.log('[Mock Cloudinary] Uploading image...');
    return 'https://cloudinary.com/mock-image.jpg';
};

const mockGetSellerByPhone = async (phone) => {
    console.log(`[Mock Spring] Getting seller for: ${phone}`);
    return { sellerId: 'mock-seller-id', name: 'Test Seller' };
};

const mockCreatePost = async (listing, urls, id) => {
    console.log('[Mock Spring] Creating post:', JSON.stringify(listing, null, 2));
    return true;
};

// --- Simulated State (Matching WhatsappBot.js) ---
const userState = new Map();
const consentedUsers = new Set();
const TARGET_GROUP = '120363406751456779@g.us';

// --- Test Helper ---
async function simulateGroupMessage(phoneNumber, body, hasMedia = false) {
    console.log(`\n--- Message from ${phoneNumber}: "${body}" ---`);
    
    if (!userState.has(phoneNumber)) {
        userState.set(phoneNumber, {
            listing: { imageUrls: [], messages: [], createdAt: Date.now(), isListing: false },
            consentPending: false,
            registrationPending: false,
            timer: null
        });
    }

    const state = userState.get(phoneNumber);
    if (hasMedia) {
        state.listing.imageUrls.push({ data: 'fake-base64', mimetype: 'image/jpeg' });
    }
    state.listing.messages.push(body);

    // Reset timer logic
    if (state.timer) clearTimeout(state.timer);

    return new Promise((resolve) => {
        state.timer = setTimeout(async () => {
            console.log(`[Timer] Running classification for ${phoneNumber}...`);
            const decision = await GeminiContextClassifier(state.listing.messages);
            console.log(`[Gemini Decision]: ${decision}`);

            if (decision === 'YES') {
                state.listing.isListing = true;
                if (consentedUsers.has(phoneNumber)) {
                    console.log('[Flow] User already consented. Processing listing...');
                    await runProcessListing(phoneNumber);
                } else {
                    console.log('[Flow] Asking for consent (Simulated DM to user)');
                    state.consentPending = true;
                }
            }
            resolve();
        }, 2000); // 2 seconds for testing instead of 5 mins
    });
}

async function simulateDM(phoneNumber, body) {
    console.log(`\n--- DM from ${phoneNumber}: "${body}" ---`);
    const state = userState.get(phoneNumber);
    if (!state) return console.log('No active session for this user.');

    const msg = body.toLowerCase().trim();
    if (msg === 'yes' && state.consentPending) {
        console.log('[Flow] Consent received. Processing listing...');
        await runProcessListing(phoneNumber);
    }
}

async function runProcessListing(phoneNumber) {
    const state = userState.get(phoneNumber);
    
    // Simulate Cloudinary
    const cloudinaryUrls = ['https://mock-url.com/img.jpg'];

    console.log('[Flow] Calling Gemini Parser...');
    const parsed = await GeminiMessageParser(state.listing.messages.join('\n'));
    
    if (!parsed) {
        console.error('[Error] Gemini failed to parse.');
        return;
    }

    const seller = await mockGetSellerByPhone(phoneNumber);
    const success = await mockCreatePost(parsed, cloudinaryUrls, seller.sellerId);
    
    if (success) {
        console.log('[Success] Listing uploaded to Mock Spring!');
        consentedUsers.add(phoneNumber);
        userState.delete(phoneNumber);
    }
}

// --- RUN TESTS ---
async function startTest() {
    console.log('Starting Bot Logic Test Harness...');
    
    // Scenario 1: User sends a listing in a group — waits for classification to finish
    await simulateGroupMessage('40712345678', 'Selling my old iPhone 12, 128GB, battery 85%. Price 300 euro. DM me if interested!', true);

    // Scenario 2: User says YES in DM — guaranteed to run after consentPending is set
    await simulateDM('40712345678', 'YES');
}

startTest();
