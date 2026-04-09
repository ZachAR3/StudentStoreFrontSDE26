const { Client, LocalAuth } = require('whatsapp-web.js');
const qrcode = require("qrcode-terminal");
const {uploadImage} = require("./services/claudinary.js");
const {GeminiMessageParser, GeminiContextClassifier} = require("./services/botGeminiService.js");
const NOT_CONSENTED_TO_MESSAGE_UPLOAD = new Set();
const consentedUsers = new Set();
const pendingResponses = new Map();
const pendingListings = new Map();
const userTimers = new Map();
const TARGET_GROUP_TEST = "120363406751456779@g.us"

// consentedUsers.add("40723136087") FOR TESTING ONLY FOR TEODOR
// g.us = gc
// c.us = private contacts
const client = new Client({
    authStrategy: new LocalAuth()
});

client.once("ready", () => console.log("Bot is ready!"));

client.on ("qr", qr => {qrcode.generate(qr, {small: true}); });

client.on("message", async msg => {
    console.log("author:", msg.author, "from:", msg.from)
    const contact = await msg.getContact()
    if (!pendingListings.has(contact.number)) {
        pendingListings.set(contact.number, {imageUrls: [], messages: [], createdAt: Date.now(), isListing: false})
    }

    if (msg.from.endsWith("@g.us") && msg.from === TARGET_GROUP_TEST ) {
        const timer = setTimeout(async() => {
            console.log("5 minutes passed, sending to gemini for context classification")
            const response = await GeminiContextClassifier(pendingListings.get(contact.number).messages)
            if (response == "YES") {
                pendingListings.get(contact.number).isListing = true
                console.log("gemini determined valid message, sending message to user")
                if (!consentedUsers.has(contact.number) && !pendingResponses.has(contact.number)) {
                    //console.log("Message to be sent in DM: Hello, this is friendly marketplace bot, do you consent to" +
                    //    "adding ur listing to marketplace? Reply with yes or no ONLY")
                    client.sendMessage(contact.number + "@c.us", "Hello, this is friendly marketplace bot, do you consent to blah blah")
                    pendingResponses.set(contact.number, contact.number)
                }
            }
            else if (response == "NO") {
                pendingListings.delete(contact.number)
                console.log("gemini determined invalid message, deleting it")
            }

        }, 300000 )

        clearTimeout(userTimers.get(contact.number))
        userTimers.set(contact.number, timer)

        if (msg.hasMedia) {
            const picture = await msg.downloadMedia();
            pendingListings.get(contact.number).imageUrls.push(picture.data)
        }

        if (msg.body !== "") {
            pendingListings.get(contact.number).messages.push(msg.body)
        }

        if (consentedUsers.has(contact.number)) {
            console.log("consented user, sending to gemini + pushing to db")
                    //TODO: push media to cloudinary, call gemini API on the description + photos
                    //TODO: after gemini, push items to db and frontend
        }

    }

    if (msg.from.endsWith("@c.us")) {
        const response = msg.body.toLowerCase();
        if (response === "yes") {
            console.log("person consented to message upload")
            consentedUsers.add(contact.number)
            //TODO: push current listing to marketplace
            pendingListings.delete(contact.number)
            pendingResponses.delete(contact.number)
        }
        else if (response === "no") {
            NOT_CONSENTED_TO_MESSAGE_UPLOAD.add(contact.number)
            console.log("person did not consent to message upload")
        }
    }

});

//a cleanup function that runs every 3 hours to delete any listing whose message has not been answered
setInterval(() => {
    pendingListings.forEach((value, key) => {
        if (value.createdAt + 1000 * 60 * 60 * 12 < Date.now()) {
            pendingListings.delete(key)
        }
    })
}, 3 * 60 * 60 * 1000)

client.initialize();