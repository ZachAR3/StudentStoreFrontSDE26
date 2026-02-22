## Project Proposal

**Team Members:** Teodor, Zachary, Chingis

**Idea Name:** Student Store-front

### 1. Product Hypothesis

* **Users:** Students and the campus community.
* **Problem:** Decentralized and unorganized sales platforms (for used goods, food, etc.).
* **Current Behavior:** Users rely on WhatsApp groups and waste time digging through old messages to see what's still for sale. Frequently, items are already sold but remain in the chat.
* **Changed Behavior:** Eliminates wasted time by making it easier to post and update item statuses. A central platform resolves the "notification nightmare" of multiple sales chats and bypasses the limits of full WhatsApp groups.

### 2. Assumptions (Initial Set)

* Users want to move away from a WhatsApp-based system to a centralized app.
* Users want to buy things on campus and list their used goods locally.
* Restaurants want a better, centralized platform to post menus and sell to students.
* Users are willing to trade "reach" for "organization."
* The friction of downloading a new app and creating an account is lower than the pain of scrolling through cluttered chats.

### 3. Reasoning & Risk Assessment

**Likely Correct / Plausible:**

* Users want to buy things on campus (evidenced by the active status of current sales and restaurant groups).
* Users are frustrated with WhatsApp and want an alternative.

**Potentially Incorrect / Risky:**

* *Restaurant Adoption:* Restaurants may be hesitant to move away from WhatsApp.
* *User Priorities:* Users might actually value "reach" over "organization."
* *Onboarding Friction:* The barrier to downloading a new app/creating an account might actually be *higher* than the annoyance of scrolling through chats.

### 4. Insights & Evolution Strategy

* **Market Validation:** We need to survey potential users and restaurants to see if they genuinely want a better solution than WhatsApp, and figure out exactly what features would incentivize them to switch.
* **Scalability:** The product can scale from a simple centralized sales app to a broader, student-led information hub. This could act as a "copilot" for navigating campus bureaucracy (like a forum) or serve as a general modular interface for any student-focused initiative (e.g., campus clubs).

### 5. Core Features

* **Three Core Modules:** General, Marketplace, and Restaurants.
* **Modular Posting:** The ability to view and post items in modular groups. Creating listings for sales or menu items should be frictionless, utilizing dedicated sales and restaurant menu templates.
* **Cross-Platform:** Website + App built with React and a Spring Boot (Kotlin) backend.
* **Auto-Moderation:** Basic content moderation (potentially AI-driven).
* **AI Recommendations:** Smart suggestions for users based on preferences.
* **Calendar Integration:** Basic syncing to display restaurant operating hours.
* **Modular "Super App" Architecture:** Built so that new features can be added easily without cluttering the interface.
* **WhatsApp Scraper (Bridge Feature):** To prevent a "split user base" from harming businesses during the transition phase, a scraper will pull from old WhatsApp groups. This will use AI auto-categorization to interpret seller names and product details and port them to the app.

### 6. Needs Further Investigation

* **Auto-Deleting Posts:** Implementing a system where posts expire (e.g., after 48 hours). If a post was scraped from WhatsApp, the system could send an automated WhatsApp reminder asking the user if the item sold or if they want to renew the listing.

