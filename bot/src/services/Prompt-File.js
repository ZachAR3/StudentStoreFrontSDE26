
function textPrompt(message) {
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


 module.exports = {textPrompt}