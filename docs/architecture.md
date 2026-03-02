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

* Create sales listings
* Listen for forwarded messages
* Regex-based keyword detection (e.g., price)
* Extract structured listing data
* Generate preview messages
* Send renewal reminders
* Provide magic links to view your listings on the web app

## Interaction Model

The bot parses user messages and forwards structured listing data to the Spring Boot API via REST endpoints.

