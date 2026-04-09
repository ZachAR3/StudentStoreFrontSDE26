const { Client, LocalAuth } = require('whatsapp-web.js');
const qrcode = require("qrcode-terminal");
const {uploadImage} = require("./services/claudinary.js");
const NOT_CONSENTED_TO_MESSAGE_UPLOAD = new Set();
const consentedUsers = new Set();
const pendingResponses = new Map();
const pendingListings = new Map();
const userTimers = new Map();

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

    if (msg.from.endsWith("@g.us")) {
        const timer = setTimeout(() => {
            console.log("2 minutes passed, sending to gemini for context classification")
            //TODO: send to Gemini for context classification a.k.a is it a listing. If yes, move on to sending message to user

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
                    //TODO: push media to cloudinary, call gemini API on the description + photos
                    //TODO: after gemini, push items to db and frontend
        }


        if (!consentedUsers.has(contact.number) && !pendingResponses.has(contact.number)) {
            console.log("Message to be sent in DM: Hello, this is friendly marketplace bot, do you consent to" +
                "adding ur listing to marketplace? Reply with yes or no ONLY")

            pendingResponses.set(contact.number, contact.number)
        }
    }

    if (msg.from.endsWith("@c.us")) {
        const response = msg.body.toLowerCase();
        if (response === "yes") {
            consentedUsers.add(contact.number)
            //TODO: push current listing to marketplace
            pendingListings.delete(contact.number)
            pendingResponses.delete(contact.number)
        }
        else if (response === "no") {
            NOT_CONSENTED_TO_MESSAGE_UPLOAD.add(contact.number)
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