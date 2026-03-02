Teodor Cristescu, [3/2/2026 3:42 PM]
# Constructor Campus Storefront

## System Architecture

# 1. Frontend Architecture (PWA Storefront)

## Stack

* Alpine.js (lightweight reactivity)
* Pico.css (mobile-first styling)
* Fetch API (REST communication)
* State-based view toggling (x-show)

## Responsibilities

* Display public listings
* Category filtering and search
* Display "Verified Student" badges
* One-click WhatsApp contact links
* Seller dashboard (via magic link)

## Design Rationale

The project intentionally avoids heavy SPA frameworks (React/Vue) due to limited scope and low state complexity. The frontend is optimized for zero-install mobile usage and simplicity.

# 2. Backend Architecture (Spring Boot – Kotlin)

## Layered Structure

Controller → Service → Repository → PostgreSQL

## Core Components

* AuthController
* ListingController
* EmailService
* TokenService
* ScheduledCleanupService
* SecurityConfiguration (JWT-based)

## Responsibilities

* Email domain validation
* OTP generation and verification
* JWT issuance and validation
* Listing creation, update, deletion
* Expiry lifecycle management
* Magic link generation

---

# 3. WhatsApp Bot Architecture

## Stack

* Node.js
* whatsapp-web.js

## Responsibilities

* Join campus sales group
* Listen for forwarded messages
* Regex-based keyword detection (e.g., price, currency indicators)
* Extract structured listing data
* Generate preview messages
* Send renewal reminders
* Send dashboard magic links

## Interaction Model

The bot parses user messages and forwards structured listing data to the Spring Boot API via REST 
