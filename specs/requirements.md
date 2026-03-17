## 1. User Stories

### 👤 Seller Stories
**Email Verification:** As a Seller, I want to verify my @constructor.university email via OTP in the bot to earn a "Verified Student" badge and build buyer trust.
**Fast Posting:** As a Seller, I want to publish items by forwarding messages with photos to the bot, eliminating manual form filling.
**Preview & Edit:** As a Seller, I want to preview my listing and edit price/description in-chat before publishing.
**Status Management:** As a Seller, I want to mark items "Sold" with one WhatsApp button, instantly removing them from the storefront.
**Dashboard Access:** As a Seller, I want passwordless dashboard access via Magic Link from the bot.

### 👤 Buyer Stories
**Instant Access:** As a Buyer, I want QR code access to the storefront without registration or app downloads.
**Trust Indicators:** As a Buyer, I want to see "Verified Student" badges to ensure seller authenticity.
**Search & Contact:** As a Buyer, I want to filter by category and contact sellers via one-click WhatsApp integration.

## 2. Functional Requirements

### 2.1 WhatsApp Bot Engine
**Message Processing:** Extract text, pricing, and media from forwarded messages and direct inputs using Gemini + traditional methods.
**Email Verification:** Validate @constructor.university domains, send 6-digit OTP, verify within 10 minutes.
**Listing Preview:** Generate visual previews with inline edit buttons (Price, Description, Confirm).
**Magic Links:** Generate secure, time-limited tokens (1-hour expiry) for dashboard access.
**Status Updates:** Provide "Mark as Sold" functionality with real-time web synchronization.

### 2.2 PWA Storefront
**Public Access:** QR code/URL access without authentication requirements.
**Verification Display:** Show "Verified Student" badges on authenticated seller listings.
**WhatsApp Integration:** "Chat on WhatsApp" buttons with pre-filled contact messages.
**Search System:** Category filters (Electronics, Books, Kitchen) and keyword search.

## 3. Non-Functional Requirements

**Progressive Web App:** Zero-install mobile experience, works in any browser.
**Real-Time Sync:** Status changes reflected on web within 2 seconds.
**Security:** Strict @constructor.university domain validation only.
**Scalability:** Configurable domain support for multi-university expansion.
**Mobile-First:** Optimized for one-handed use, 90% mobile traffic expected.
