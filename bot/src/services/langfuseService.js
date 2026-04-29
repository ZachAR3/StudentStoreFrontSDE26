const { Langfuse } = require("langfuse");

const langfuse = new Langfuse({
    publicKey: process.env.LANGFUSE_PUBLIC_KEY,
    secretKey: process.env.LANGFUSE_SECRET_KEY,
    baseUrl: process.env.LANGFUSE_BASEURL,
});

process.on("beforeExit", async () => {
    await langfuse.shutdownAsync();
});

module.exports = langfuse;
