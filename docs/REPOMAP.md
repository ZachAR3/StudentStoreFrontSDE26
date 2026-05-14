# Repository Map & API Documentation

This document provides a human-readable overview of the Student-Store Front project, its file structure, and its technical interfaces.

## 1. Project Overview
A campus-specific marketplace designed to move student sales from chaotic WhatsApp groups into a structured, verified web platform.

**Tech Stack:**
- **Backend:** Spring Boot (Kotlin), Spring Data JPA, PostgreSQL, Spring Security (JWT).
- **Frontend:** Alpine.js, Pico.css (served as a PWA from static resources).
- **Integration:** Node.js (WhatsApp Web.js Bot), communicating with the backend.

---

## 2. Repository Structure

```text
.
├── docs/                      # Project documentation
│   ├── architecture.md        # System design and layers
│   ├── database/              # Database course deliverables: requirement map, domain description, SQL, DBML, schema images, seed/query/demo packs
│   ├── frontend-modularity-implementation-plan.md # Frontend refactor/build plan
│   ├── PROPOSAL.md            # Original project hypothesis
│   ├── REPOMAP.md             # [This File] Repo map and API spec
│   ├── THEMEMAP.md            # Frontend design system and theme reference
│   └── FrameworksPresentation/ # Presentation assets
├── specs/                     # Requirement specifications
├── src/
│   ├── main/
│   │   ├── kotlin/com/studentstorefront/
│   │   │   ├── config/        # Security (JWT, Spring Security), OpenApi, and DataLoader legacy repairs
│   │   │   ├── controller/    # REST Endpoints (Auth, Favourite, Post, Review, User, WhatsAppQrLogin)
│   │   │   ├── dto/           # Data Transfer Objects (request, response, update)
│   │   │   ├── entity/        # JPA Entities (Favourite, PasswordResetToken, Post, PostMedia, Review, User, Sale, WhatsAppLoginSession)
│   │   │   ├── enums/         # Enums (Category, PostStatus, ReviewDirection, Role, WhatsAppSessionStatus)
│   │   │   ├── exception/     # Global Exception Handling
│   │   │   ├── repository/    # Database Access Layers (EmailVerificationToken, Favourite, PasswordResetToken, Post, PostMedia, Review, User, Sale, WhatsAppLoginSession)
│   │   │   ├── scheduler/     # Listing expiration reminders and archival jobs
│   │   │   └── service/       # Business Logic (BotNotificationService, CloudinaryService, EmailService, FavouriteService, JwtService, PasswordResetService, PostService, ReviewService, UserService, WhatsAppQrLoginService)
│   │   └── resources/
│   │       ├── db/
│   │       │   └── migration/ # Flyway database migration scripts
│   │       ├── static/
│   │       │   ├── config/layouts/ # Static layout JSON definitions used by the frontend runtime
│   │       │   ├── css/       # Split frontend stylesheets (tokens, shell, layout system, elements, forms, profile, builder)
│   │       │   ├── js/
│   │       │   │   ├── core/  # Namespace, storage, router, API client, registries, validators, runtime, adapters
│   │       │   │   ├── services/ # Thin REST contract modules around frontend/backend endpoints
│   │       │   │   ├── stores/ # Domain state modules (auth, marketplace, favourites, profile, upload, builder)
│   │       │   │   ├── elements/ # Element definitions for layout-driven rendering, including marketplace, restaurant, profile, and reusable sales-site sections
│   │       │   │   ├── builder/ # Builder helpers (palette, drag-drop, inspector, preview)
│   │       │   │   └── data/  # Categories, default layouts, sample data
│   │       │   ├── index.html # SPA shell and Alpine templates
│   │       │   ├── theme-lab.html # Static theme audit sandbox
│   │       │   └── js/app.js  # Frontend composition entrypoint
│   │       └── application.properties # Database, JWT & WhatsApp bot config
│   └── test/
│       └── kotlin/com/studentstorefront/
│           ├── service/       # Unit tests (UserServiceTest, WhatsAppQrLoginServiceTest — MockK)
│           └── controller/    # Integration tests (WhatsAppQrLoginControllerTest, PostSearchControllerTest — MockMvc + Testcontainers)
├── bot/                       # Node.js WhatsApp Bot integration
│   ├── src/
│   │   ├── WhatsappBot.js     # Main bot script: group listing capture, DM post flow, consent flow, QR login, shopping chat delegation
│   │   ├── shopping/
│   │   │   └── WhatsAppShoppingChat.js # DM shopping commands, n/p pagination, cover-image details, seller contact links
│   │   ├── services/llm/
│   │   │   ├── modelRouter.js # Provider router with primary+fallback chain for runtime LLM selection
│   │   │   ├── parseJsonFromLlmText.js # Shared JSON/array extraction utility for wrapped LLM output
│   │   │   └── providers/
│   │   │       ├── geminiProvider.js # Gemini provider adapter with current fallback defaults and multimodal parsing
│   │   │       └── openaiCompatibleProvider.js # OpenAI-style adapter for GPT/LiteLLM multimodal parsing
│   │   ├── services/
│   │   │   ├── BotStateStore.js      # Persistent per-user draft/consent/registration state with timer helpers
│   │   │   ├── ListingSubmissionService.js # Listing parsing, image preprocessing, upload, and Spring submission orchestration
│   │   │   ├── chatIdentity.js       # Helpers for DM/group detection and chat/contact id normalization
│   │   │   ├── imagePreprocessor.js  # Downscales images before LLM parsing to reduce latency and payload size
│   │   │   ├── langfuseService.js    # Shared Langfuse client for LLM observability
│   │   │   ├── springServices.js     # Spring Boot REST API calls for listing creation, lookup, search, and WhatsApp login
│   │   │   ├── webhookService.js     # Express webhook server (user-registered and outbound send-message bridge)
│   │   │   ├── claudinary.js         # Cloudinary image upload helper
│   │   │   └── Prompt-File.js        # Prompt templates (classification, parsing, image description)
│   │   └── tests/
│   │       ├── LLM_Services_For_Testing/
│   │       │   ├── botGeminiService.js   # Legacy Gemini-only service (kept for compatibility/tests)
│   │       │   └── Gemma4Service.js      # Gemma via LiteLLM (benchmark/testing only)
│   │       ├── GeminiAPI-test-Mock.js  # Standalone test harness with mocked services
│   │       ├── runBenchmark.js         # Text benchmark: Gemini vs Gemma vs ChatGPT
│   │       └── Benchmark_Images.js     # Image description benchmark: Gemma vs ChatGPT vs Gemini
│   ├── .env.example           # Bot environment template (provider routing, OpenAI vars, Gemini fallback vars)
│   └── package.json           # Bot dependencies (includes `openai` SDK for OpenAI-compatible providers)
└── docker-compose.yaml        # Local PostgreSQL setup
```

---

## 3. API Specification (REST)

### **Authentication** (`/api/auth`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/register` | Public | Create or refresh a pending user registration, normalize the phone number, and send an email verification code |
| `POST` | `/api/auth/login` | Public | Authenticate with email/password and receive a JWT Bearer token |
| `POST` | `/api/auth/forgot-password`| Public | Request a password reset link (sent via email) |
| `POST` | `/api/auth/reset-password` | Public | Reset password using a valid token |
| `POST` | `/api/auth/whatsapp/session` | Public | Create a WhatsApp QR login session; returns `sessionId`, `qrContent`, and `expiresAt` |
| `GET` | `/api/auth/whatsapp/session/{sessionId}` | Public | Poll session status; returns `PENDING`, `COMPLETED` (with `claimToken`), `EXPIRED`, `PHONE_NOT_LINKED`, or `CLAIMED` |
| `POST` | `/api/auth/whatsapp/confirm` | `X-Bot-Api-Key` | Bot-only: confirm a login by supplying `loginToken` + `phoneNumber`; only enabled users can complete the flow |
| `POST` | `/api/auth/whatsapp/claim` | Public | Exchange a one-time `claimToken` for a JWT; stale sessions tied to missing/unverified users are rejected |

### **Post Management** (`/api/posts`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/posts` | Public | Fetch paginated list of all posts |
| `POST` | `/api/posts` | USER/ADMIN | Create a new listing (JSON) |
| `POST` | `/api/posts/upload` | USER/ADMIN | Create a new listing with multiple image file uploads (multipart/form-data) |
| `POST` | `/api/posts/bot` | `X-Bot-Api-Key` | Bot-only: Create one marketplace listing from parsed WhatsApp output, including reusable image hashes |
| `POST` | `/api/posts/bot/media/resolve` | `X-Bot-Api-Key` | Bot-only: Resolve known media URLs by SHA-256 image hash so duplicate image bytes can be reused |
| `POST` | `/api/posts/bot/renew/{id}` | `X-Bot-Api-Key` | Bot-only: Renew an archived/expired post, set it `ACTIVE`, extend `expiresAt`, and clear reminder state |
| `GET` | `/api/posts/{id}` | Public | Get details of a specific post |
| `PUT` | `/api/posts/{id}` | USER/ADMIN | Update an existing post; service-layer ownership rules allow only the original poster or an admin |
| `PUT` | `/api/posts/{id}/upload` | USER/ADMIN | Update an existing post with multipart form data, including keeping, removing, reordering, and uploading listing images |
| `PATCH` | `/api/posts/{id}/mark-sold`| USER/ADMIN | Mark a post as sold with a required registered `buyerId`, store sale metadata, and request a buyer review |
| `DELETE` | `/api/posts/{id}` | USER/ADMIN | Delete a post |
| `GET` | `/api/posts/available` | Public | Get only unsold listings |
| `GET` | `/api/posts/search?q={text}&category={name}` | Public | Search unsold listings by title/description with optional category filter; supports pageable `page`, `size`, and `sort` params |
| `GET` | `/api/posts/category/{name}`| Public | Filter posts by category (e.g., ELECTRONICS, BOOKS) |
| `GET` | `/api/posts/seller/{id}` | Public | Get all posts by a specific user (legacy endpoint name kept for compatibility) |

### **User Management** (`/api/users`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/users` | ADMIN | Fetch paginated list of all users |
| `POST` | `/api/users` | ADMIN | Create a new user directly; phone uniqueness uses normalized `+digits` storage just like self-registration |
| `GET` | `/api/users/{id}` | USER/ADMIN | Get details of a specific user |
| `GET` | `/api/users/email/{email}`| ADMIN | Get details of a specific user by email |
| `GET` | `/api/users/by-phone` | Public* | Bot-specific: Lookup an enabled registered user by phone number; unverified accounts are treated as not registered |
| `GET` | `/api/users/search?q={text}` | USER/ADMIN | Search enabled registered users by name/email for buyer selection when marking a listing sold |
| `PUT` | `/api/users/{id}` | USER/ADMIN | Update an existing user |
| `DELETE` | `/api/users/me` | USER/ADMIN | Self-service account deletion (requires password re-entry) and clears tokens, QR login sessions, user-owned favourites, favourites/reviews attached to the user's posts, buyer links, and owned posts before removing the user |
| `DELETE` | `/api/users/{id}` | ADMIN | Delete a user with the same dependency cleanup used by self-service deletion |

### **Review Management** (`/api/reviews`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/reviews` | USER/ADMIN | Submit one transaction review for the authenticated user; backend derives buyer-to-seller or seller-to-buyer direction from the sold post |
| `GET` | `/api/reviews/context/{postId}` | USER/ADMIN | Return review labels/context for a sold transaction, including whether the authenticated user already reviewed it |
| `GET` | `/api/reviews/pending` | USER/ADMIN | Return sold transactions where the authenticated user can still leave a review |
| `GET` | `/api/reviews/profile/{userId}` | USER/ADMIN | Return seller-rating summary, buyer-rating summary, and recent reviews for a profile |

### **Favourite Management** (`/api/favourites`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/favourites` | USER/ADMIN | Return the authenticated user's favourite post ids |
| `POST` | `/api/favourites/{postId}` | USER/ADMIN | Add a post to the authenticated user's favourites |
| `DELETE` | `/api/favourites/{postId}` | USER/ADMIN | Remove a post from the authenticated user's favourites |

---

## 4. Data Model (Core Entities)

### **Post**
- `postId`: Primary Key (Long)
- `title`: Item name (String)
- `price`: Item cost (BigDecimal)
- `description`: Detailed info (String)
- `category`: Classification Enum (ELECTRONICS, BOOKS, CLOTHING, FURNITURE, SPORTS, FOOD, SERVICES, OTHER)
- `isSold`: Boolean status flag
- `createdAt`: Timestamp
- `expiresAt`: Optional expiration timestamp; new posts default to two days from creation
- `status`: Lifecycle Enum (`ACTIVE`, `ARCHIVED`). Public/search queries use `ACTIVE`; expired posts are archived, not hard-deleted.
- `reminderSentAt`: Timestamp marking that the pre-expiration WhatsApp reminder was sent
- `soldAt`: Timestamp when the seller recorded the sale
- `user`: Reference to a `User` (the seller)
- `buyer`: Optional reference to the registered `User` account that purchased the item
- `postMedia`: Collection of `PostMedia` objects

### **Sale**
- `id`: Primary Key (Long)
- `post`: Reference to the `Post` that was sold
- `seller`: Reference to the `User` who sold the item
- `buyer`: Reference to the `User` who bought the item
- `soldAt`: Timestamp when the sale occurred
- `createdAt`: Record creation timestamp

### **PostMedia**
- `id`: Primary Key (Long)
- `mediaUrl`: URL to the hosted image (String)
- `imageHash`: Optional SHA-256 hash of the original image bytes, used by the bot/backend to reuse stored URLs for duplicate images
- `displayOrder`: User-defined order for image display (Integer)
- `isCover`: Flag indicating the primary thumbnail (Boolean)
- `post`: Reference to the parent `Post`

### **User**
- `userId`: Primary Key (Long)
- `name`: Full name
- `email`: Verification email (@constructor.university)
- `phoneNumber`: WhatsApp contact info
- `password`: Encrypted password
- `role`: Enum role (USER, ADMIN)
- `isEnabled`: Boolean active status flag
- `createdAt`: Timestamp

Lifecycle notes:
- Public registration creates or refreshes a disabled user record until email verification completes.
- Re-registering with the same unverified email or phone refreshes that pending account instead of permanently burning the identifier after an abandoned or failed signup.
- User deletion clears dependent tokens, WhatsApp QR sessions, user-owned favourites, favourites/reviews attached to the user's listings, buyer references, and owned posts before the user row is removed, allowing the same email/phone to be reused later.

### **Favourite**
- `id`: Primary Key (Long)
- `user`: Reference to the owning `User`
- `post`: Reference to the favourited `Post`
- Uniqueness is enforced per user/post pair at the service layer and repository level

### **Review**
- `reviewId`: Primary Key (Long)
- `post`: Reference to the sold `Post`
- `reviewer`: Reference to the account submitting the review
- `reviewee`: Reference to the account being reviewed
- `direction`: Enum (`BUYER_TO_SELLER`, `SELLER_TO_BUYER`)
- `rating`: Integer rating from 1 to 5
- `comment`: Optional written review, capped at 500 characters
- `createdAt`: Review creation timestamp
- Uniqueness is enforced per post/direction so each transaction can have at most one buyer review and one seller review

### **PasswordResetToken**
- `id`: Primary Key (Long)
- `token`: Unique reset token (String)
- `user`: Reference to the `User`
- `expiryDate`: Timestamp after which token is invalid

### **WhatsAppLoginSession** (table: `whatsapp_login_sessions`)
- `id`: Primary Key (UUID)
- `sessionId`: UUID shared with the frontend for polling (unique)
- `loginToken`: UUID embedded in the QR code / wa.me deep link (unique)
- `claimToken`: UUID issued on bot confirmation, exchanged once for a JWT (unique, nullable)
- `status`: Enum — `PENDING` → `COMPLETED` → `CLAIMED`; or `EXPIRED` / `PHONE_NOT_LINKED`
- `userId`: FK to `users.user_id`
- `phoneNumber`: Raw phone number received from bot
- `creatorIp`: IP address of the browser that created the session
- `createdAt`: Session creation timestamp
- `expiresAt`: `createdAt + 5 minutes`
- `completedAt`: Timestamp when bot confirmed the login
- `claimedAt`: Timestamp when frontend exchanged claimToken for JWT

---

## 5. Database Course Deliverables

The repository now includes a dedicated `docs/database/` package for database-course submission materials derived from the implemented Spring/JPA model:

- **`docs/database/README.md`**: Requirement checklist mapping the current project to the course deliverables and pointing to the canonical database artifacts.
- **`docs/database/domain-and-scenarios.md`**: Course-facing domain description written so the schema can be reconstructed from text alone, plus explicit critical user paths such as registration/verification, listing creation, search, sale completion, reviews, and WhatsApp login.
- **`docs/database/schema.sql`**: PostgreSQL DDL for the implemented entities, constraints, and documented index strategy.
- **`docs/database/schema.dbml`**: DBML export of the same schema for tools such as dbdiagram.io.
- **`docs/database/schema.svg` / `schema.png`**: Schema diagram images for submission portals, reports, and presentation decks.
- **`docs/database/seed.sql`**: Deterministic fake dataset covering enabled and pending users, active and archived listings, sold transactions, favourites, reviews, reset tokens, verification tokens, and WhatsApp login sessions.
- **`docs/database/queries.sql`**: Three course-facing SQL queries aligned with real application behavior: marketplace search, expiration reminder scanning, and profile-review aggregation.
- **`docs/database/query-results.md`**: Expected outputs for the seeded dataset so the queries can be validated quickly.
- **`docs/database/demo-and-deployment.md`**: MVP recording script and placeholders for the final deployment URL and demo video URL.

Important implementation alignment:

- The database pack mirrors the Kotlin entities in `src/main/kotlin/com/studentstorefront/entity`.
- The SQL schema adds explicit indexes for the app’s dominant query paths: active listing reads, category-filtered search, expiration scheduling, per-user profile lookups, ordered media retrieval, review summaries, and token/session lookups.
- Seeded demo accounts use a valid BCrypt password hash so the seeded database can support an actual login demo rather than only static schema evaluation.
- Flyway migrations in `src/main/resources/db/migration/` ensure the production database schema remains in sync with the JPA model.

---
... [rest of the file unchanged] ...
