---
marp: true
theme: default
paginate: true
size: 16:9
title: Student Storefront Pre-Defense
header: "Student Storefront | Pre-defense"
footer: "Teodor, Zachary, Chingis"
style: |
  :root {
    --ink: #17211c;
    --muted: #5f6f66;
    --campus: #0f7b5f;
    --campus-2: #f2b84b;
    --panel: #f7f3e8;
  }
  section {
    font-family: Avenir Next, Avenir, Helvetica, Arial, sans-serif;
    color: var(--ink);
    background: linear-gradient(135deg, #fffaf0 0%, #eef7ef 100%);
    font-size: 28px;
    letter-spacing: -0.01em;
  }
  h1, h2, h3 { color: var(--ink); letter-spacing: -0.04em; }
  h1 { font-size: 64px; }
  h2 { font-size: 44px; }
  strong { color: var(--campus); }
  em { color: var(--muted); }
  code { background: #e8efe9; color: #0f5f4b; border-radius: 6px; padding: 0.1em 0.3em; }
  table { font-size: 22px; }
  section.lead {
    background: radial-gradient(circle at 15% 20%, rgba(242,184,75,.35), transparent 26%),
                radial-gradient(circle at 88% 72%, rgba(15,123,95,.23), transparent 30%),
                linear-gradient(135deg, #fff8e5 0%, #eaf6f0 100%);
  }
  .two { display: grid; grid-template-columns: 1fr 1fr; gap: 36px; align-items: start; }
  .card { background: rgba(255,255,255,.72); border: 1px solid rgba(23,33,28,.12); border-radius: 18px; padding: 22px; box-shadow: 0 14px 35px rgba(23,33,28,.08); }
  .small { font-size: 22px; color: var(--muted); }
  .big { font-size: 54px; font-weight: 800; color: var(--campus); }
  .demo { border-left: 8px solid var(--campus-2); padding-left: 22px; }
  .warn { color: #8a4d00; }
---

<!-- _class: lead -->

# Student Storefront
### Turning campus WhatsApp sales into a verified marketplace

**Pre-defense goal:** show product value, technical uncertainty solved, and a demo path that works in 10 minutes.

<!--
Speaker notes:
Target: 25s. State the goal: this is not a feature list. We are showing why the product matters, what is working now, and which technical risks we solved.
-->

---

## Problem: WhatsApp sells, but does not organize

<div class="two">
<div class="card">

### Current behavior
- Items posted in scattered groups
- Sold items stay visible
- Search depends on chat history
- Buyer trust depends on guessing
- WhatsApp groups hit user limits. 

</div>
<div class="card">

### Cost to students
- Repeated "still available?" messages
- Lost posts after a few hours
- No structured categories or status
- No campus-only seller verification

</div>
</div>

<!--
Speaker notes:
Target: 35s. Make this concrete: campus sales already happen, but the channel is optimized for conversation, not inventory. The product value is not inventing sales; it is removing friction from an existing behavior.
-->

---

## Product thesis: keep WhatsApp reach, add marketplace structure

<div class="two">
<div class="card">

### Buyers get
- Public PWA storefront
- Search, category filter, sorting
- Multi-photo listings
- One-click WhatsApp contact

</div>
<div class="card">

### Sellers get
- Verified `@constructor.university` identity
- Listing creation and management
- Mark sold / delete from profile
- WhatsApp bridge for lower-friction posting

</div>
</div>

<!--
Speaker notes:
Target: 35s. Emphasize why users would switch: we do not force them to abandon WhatsApp. WhatsApp remains the contact and optional posting channel; the web app becomes the source of truth.
-->

---

## What works now

| Area | Current implementation |
|---|---|
| Storefront | Alpine.js PWA served by Spring Boot |
| Backend | Kotlin Spring Boot, JPA, PostgreSQL |
| Auth | JWT login/register with campus email validation |
| Listings | Create, upload images, browse, filter, sort, mark sold |
| Trust | Seller profiles, phone/email contact, verified campus domain |
| Integration | Node WhatsApp bot, Gemini parsing, Cloudinary uploads |

<!--
Speaker notes:
Target: 35s. Keep this factual. Mention that the repo has a usable vertical slice, not just mockups: UI, API, DB, security, and integration services.
-->

---

## Demo checkpoint 1: buyer experience

<div class="demo">

### Show in the app
1. Open **Campus Store** listings
2. Search for an item
3. Filter by category and sort by price/newest
4. Open seller/contact affordances
5. Save a favourite if logged in

</div>

**Claim:** a buyer can find active campus inventory faster than scrolling WhatsApp history.

<!--
Speaker notes:
Target: 60s including live demo. If the app is slow, use seeded data from DataLoader or existing DB. Do not explain implementation here; let the demo prove the product value.
-->

---

## Demo checkpoint 2: seller workflow

<div class="demo">

### Show in the app
1. Log in or register with `@constructor.university`
2. Create listing with title, category, price, description
3. Upload multiple images and preview them
4. Visit profile dashboard
5. Mark item sold or delete listing

</div>

**Claim:** listing state becomes explicit; buyers stop seeing stale posts.

<!--
Speaker notes:
Target: 70s including live demo. If Cloudinary is unavailable, say one sentence: image upload depends on env config, then show existing listing management instead. Do not debug live.
-->

---

## Technical challenge 1: two entry points, one source of truth

<div class="two">
<div class="card">

### Uncertainty
How do we keep WhatsApp convenience without making WhatsApp the database?

</div>
<div class="card">

### Decision
Spring Boot owns listings and auth. The bot only translates chat interactions into backend API calls.

</div>
</div>

**Result:** web actions and bot actions converge on the same `Post`, `Seller`, and `PostMedia` model.

<!--
Speaker notes:
Target: 50s. This is a real technical challenge because the risk is duplicated state and inconsistent sold status. The solution is clear boundary setting: backend is source of truth; bot is an adapter.
-->

---

## Technical challenge 2: WhatsApp QR login safely bridges devices

```text
Browser -> POST /api/auth/whatsapp/session
Backend -> sessionId + wa.me login token
Phone   -> sends login:<token> to bot
Bot     -> POST /api/auth/whatsapp/confirm with X-Bot-Api-Key
Browser -> polls session, claims one-time token
Backend -> returns JWT
```

**Risk handled:** tokens expire, claim token is single-use, bot endpoint uses a shared secret.

<!--
Speaker notes:
Target: 60s. This is stronger than saying "we used JWT". Explain the uncertainty: the user begins on desktop/browser but confirms identity from WhatsApp. We solved it with a short-lived polling session and one-time claim token.
-->

---

## Demo checkpoint 3: WhatsApp integration

<div class="demo">

### Preferred live demo
1. Click WhatsApp login
2. Show QR / `wa.me` deep link
3. Send prefilled token to bot
4. Watch browser poll and receive JWT

### Fallback demo
Show the sequence diagram and API responses from `docs/REPOMAP.md`.

</div>

**Rule:** if the bot or WhatsApp session fails, spend one sentence and move on.

<!--
Speaker notes:
Target: 45s. This is intentionally optional because external services are risky in a defense. Have terminal/API screenshots or docs open as a fallback.
-->

---

## Technical challenge 3: images and structured listings

<div class="two">
<div class="card">

### Product need
Listings without images are low-trust and low-conversion.

</div>
<div class="card">

### Engineering path
- Multipart upload endpoint
- Up to 10 images
- Cover image ordering
- Cloudinary-hosted media URLs
- Gemini-assisted bot parsing

</div>
</div>

**Learning:** media upload turns a simple CRUD app into a workflow with validation, storage, ordering, and failure handling.

<!--
Speaker notes:
Target: 45s. Avoid overclaiming. The important point is that this is not just a form; image handling affects data model, API shape, UI preview, external storage, and bot ingestion.
-->

---

## Evidence: implemented and tested vertical slices

<div class="two">
<div class="card">

### Working slices
- Public browse and search
- Authenticated create listing
- Profile listing management
- Favourites sync
- WhatsApp QR login endpoints

</div>
<div class="card">

### Test coverage focus
- `WhatsAppQrLoginServiceTest`: service branches
- `WhatsAppQrLoginControllerTest`: HTTP flow with Testcontainers
- Validation in frontend and backend DTO paths

</div>
</div>

<!--
Speaker notes:
Target: 35s. Evidence must be concrete. Mention tests for the riskiest auth integration, not just generic "we tested it".
-->

---

## Known gaps before final defense

| Gap | Why it matters | Next action |
|---|---|---|
| Public `POST /api/sellers` | Admin bypass risk | Restrict to admin role |
| Public phone lookup | PII exposure risk | Require `X-Bot-Api-Key` |
| Live bot dependency | Demo fragility | Keep recording/API fallback |
| User validation | Product value evidence | Run small campus test with real listings |

<!--
Speaker notes:
Target: 40s. This improves credibility. Present gaps as controlled engineering risk, not surprises. Tie each gap to a next action.
-->

---

## Final defense focus

<div class="card">

### What we want the jury to remember
**Student Storefront is a campus marketplace where WhatsApp remains the social channel, while the backend becomes the trusted inventory system.**

</div>

### Next sprint priorities
- Lock down bot-only/admin endpoints
- Stabilize demo data and fallback recording
- Validate with real student listings
- Polish mobile-first PWA flow

<!--
Speaker notes:
Target: 25s. End with the one-sentence takeaway and concrete sprint priorities. Do not introduce new features here.
-->
