
function UserMessagePrompt(message) {
    return `Extract from this text ${message} the following fields: price, title, 
description and category. For the category field, choose only ONE of the following: Electronics, 
Clothing, Furniture, Books, Sports, Food, Services, Other. The output should be ONLY in JSON format with no markdown, 
no extra text and no explanations. Use the following format: {
  "title": "...",
  "price": ...,
  "description": "...",
  "category": "..."
}`
}

function classificationPrompt(messages) {
    const allMessages = messages.join("\n")
    return `Analyze the following group of messages separated by newline ${allMessages}. Determine using context validation if the
     messages constitute a valid listing for a student marketplace. Reply with "YES" if the following criteria is met: the messages show a clear intention of selling
     the mentioned object(e.g "Selling my laptop 500€", "iPhone 13 for sale, DM me", "Selling portable mini Google smart TV  Condition: used, perfectly working" , a price 
     is either specified OR there's an indication price will be discussed ( via DM or group messages) . Reply "NO" for "just group questions ( e.g Who sells monitors?"), 
     group conversations, spam or irrelevant messages. Reply with ONLY YES or NO, nothing else, no punctuation`
}

module.exports = {UserMessagePrompt, classificationPrompt}
