const { Client, LocalAuth } = require('whatsapp-web.js');
const qrcode = require("qrcode-terminal");

const NOT_CONSENTED_TO_MESSAGE_UPLOAD = new Set();
const consentedUsers = new Set();
const pendingResponses = new Map();

const client = new Client({
    authStrategy: new LocalAuth()
});

client.once("ready", () => console.log("Bot is ready!"));

client.on ("qr", qr => {qrcode.generate(qr, {small: true}); });

client.on("message", msg => {
    if (!consentedUsers.has(msg.author) && pendingResponses.get(msg.author) !== "pending response"
        && msg.from.endsWith("@g.us")) {
            //client.sendMessage(msg.author, "some message");
            console.log("Message is being sent to user");
            pendingResponses.set(msg.author, "pending response");
    }
    else if (consentedUsers.has(msg.author)) {
        console.log("Message is being uploaded to platform")
    }
    if (msg.from.endsWith("@c.us")) {
        if (msg.body === "yes") {
            consentedUsers.add(msg.from);
            pendingResponses.delete(msg.from);
           // client.sendMessage(msg.from, "Consent has been registered");
            console.log("Consent has been registered");
        }
        else if (msg.body === "no") {
            NOT_CONSENTED_TO_MESSAGE_UPLOAD.add(msg.from);
            pendingResponses.delete(msg.from);
           // client.sendMessage(msg.from, "Consent has been denied");
            console.log("Consent has been denied");
        }
    }
});

client.initialize();