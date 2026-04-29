# Repository Map & API Documentation

This document provides a human-readable overview of the Student Storefront project, its file structure, and its technical interfaces.

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
│   ├── PROPOSAL.md            # Original project hypothesis
│   ├── REPOMAP.md             # [This File] Repo map and API spec
│   └── FrameworksPresentation/ # Presentation assets
├── specs/                     # Requirement specifications
├── src/
│   ├── main/
│   │   ├── kotlin/com/studentstorefront/
│   │   │   ├── config/        # Security (JWT, Spring Security), OpenApi, and DataLoader
│   │   │   ├── controller/    # REST Endpoints (Auth, Favourite, Post, Seller, WhatsAppQrLogin)
│   │   │   ├── dto/           # Data Transfer Objects (request, response, update)
│   │   │   ├── entity/        # JPA Entities (Favourite, PasswordResetToken, Post, PostMedia, Seller, WhatsAppLoginSession)
│   │   │   ├── enums/         # Enums (Category, Role, WhatsAppSessionStatus)
│   │   │   ├── exception/     # Global Exception Handling
│   │   │   ├── repository/    # Database Access Layers (Favourite, PasswordResetToken, Post, PostMedia, Seller, WhatsAppLoginSession)
│   │   │   └── service/       # Business Logic (CloudinaryService, EmailService, FavouriteService, JwtService, PasswordResetService, PostService, SellerService, WhatsAppQrLoginService)
│   │   └── resources/
│   │       ├── static/        # Frontend (index.html, css/custom.css, js/app.js)
│   │       └── application.properties # Database, JWT, Email & WhatsApp bot config
│   └── test/
│       └── kotlin/com/studentstorefront/
│           ├── service/       # Unit tests (WhatsAppQrLoginServiceTest — MockK)
│           └── controller/    # Integration tests (WhatsAppQrLoginControllerTest — MockMvc + Testcontainers)
├── bot/                       # Node.js WhatsApp Bot integration
│   ├── src/
│   │   ├── WhatsappBot.js     # Main bot script: consent flow + QR login handler
│   │   ├── services/
│   │   │   ├── botGeminiService.js   # Production LLM: Gemini with 4-model fallback chain
│   │   │   ├── Gemma4Service.js      # Local LLM via Ollama (benchmark/testing only — not used by bot)
│   │   │   ├── langfuseService.js    # Shared Langfuse client for LLM observability
│   │   │   ├── springServices.js     # Spring Boot REST API calls
│   │   │   ├── webhookService.js     # Express webhook server (seller-registered)
│   │   │   ├── claudinary.js         # Cloudinary image upload helper
│   │   │   └── Prompt-File.js        # Gemini/Gemma prompt templates
│   │   └── tests/
│   │       ├── GeminiAPI-test-Mock.js  # Standalone test harness with mocked services
│   │       └── runBenchmark.js         # Head-to-head Gemini vs Gemma benchmark (requires Ollama)
│   └── package.json           # Bot dependencies
└── docker-compose.yaml        # Local PostgreSQL setup
```

---

## 3. API Specification (REST)

### **Authentication** (`/api/auth`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/auth/register` | Public | Register a new seller and receive a JWT Bearer token |
| `POST` | `/api/auth/login` | Public | Authenticate with email/password and receive a JWT Bearer token |
| `POST` | `/api/auth/forgot-password`| Public | Request a password reset link (sent via email) |
| `POST` | `/api/auth/reset-password` | Public | Reset password using a valid token |
| `POST` | `/api/auth/whatsapp/session` | Public | Create a WhatsApp QR login session; returns `sessionId`, `qrContent`, and `expiresAt` |
| `GET` | `/api/auth/whatsapp/session/{sessionId}` | Public | Poll session status; returns `PENDING`, `COMPLETED` (with `claimToken`), `EXPIRED`, `PHONE_NOT_LINKED`, or `CLAIMED` |
| `POST` | `/api/auth/whatsapp/confirm` | `X-Bot-Api-Key` | Bot-only: confirm a login by supplying `loginToken` + `phoneNumber` |
| `POST` | `/api/auth/whatsapp/claim` | Public | Exchange a one-time `claimToken` for a JWT |

### **Post Management** (`/api/posts`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/posts` | Public | Fetch paginated list of all posts |
| `POST` | `/api/posts` | SELLER/ADMIN | Create a new listing (JSON) |
| `POST` | `/api/posts/upload` | SELLER/ADMIN | Create a new listing with multiple image file uploads (multipart/form-data) |
| `POST` | `/api/posts/bot` | `X-Bot-Api-Key` | Bot-only: Create a new listing (e.g., from group chat parsing) |
| `GET` | `/api/posts/{id}` | Public | Get details of a specific post |
| `PUT` | `/api/posts/{id}` | SELLER/ADMIN | Update an existing post |
| `PATCH` | `/api/posts/{id}/mark-sold`| SELLER/ADMIN | Mark a post as sold (removes from active feed) |
| `DELETE` | `/api/posts/{id}` | SELLER/ADMIN | Delete a post |
| `GET` | `/api/posts/available` | Public | Get only unsold listings |
| `GET` | `/api/posts/category/{name}`| Public | Filter posts by category (e.g., ELECTRONICS, BOOKS) |
| `GET` | `/api/posts/seller/{id}` | Public | Get all posts by a specific seller |

### **Seller Management** (`/api/sellers`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/sellers` | ADMIN | Fetch paginated list of all sellers |
| `POST` | `/api/sellers` | Public* | Create a new seller directly (Vulnerable: use `/auth/register`) |
| `GET` | `/api/sellers/{id}` | SELLER/ADMIN | Get details of a specific seller |
| `GET` | `/api/sellers/email/{email}`| ADMIN | Get details of a specific seller by email |
| `GET` | `/api/sellers/by-phone` | Public* | Bot-specific: Lookup seller by phone number (Vulnerable: leaks PII) |
| `PUT` | `/api/sellers/{id}` | SELLER/ADMIN | Update an existing seller |
| `DELETE` | `/api/sellers/me` | SELLER/ADMIN | Self-service account deletion (requires password re-entry) |
| `DELETE` | `/api/sellers/{id}` | ADMIN | Delete a seller |

### **Favourites** (`/api/favourites`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/favourites` | SELLER/ADMIN | Get a list of `postId`s that the current user has favourited |
| `POST` | `/api/favourites/{postId}` | SELLER/ADMIN | Add a post to user's favourites |
| `DELETE` | `/api/favourites/{postId}`| SELLER/ADMIN | Remove a post from user's favourites |

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
- `expiresAt`: Optional expiration timestamp
- `seller`: Reference to a `Seller`
- `postMedia`: Collection of `PostMedia` objects

### **PostMedia**
- `id`: Primary Key (Long)
- `mediaUrl`: URL to the hosted image (String)
- `displayOrder`: User-defined order for image display (Integer)
- `isCover`: Flag indicating the primary thumbnail (Boolean)
- `post`: Reference to the parent `Post`

### **Seller**
- `sellerId`: Primary Key (Long)
- `name`: Full name
- `email`: Verification email (@constructor.university)
- `phoneNumber`: WhatsApp contact info
- `password`: Encrypted password
- `role`: Enum role (SELLER, ADMIN)
- `isEnabled`: Boolean active status flag
- `createdAt`: Timestamp

### **Favourite**
- `id`: Primary Key (Long)
- `seller`: Reference to the `Seller` who favourited
- `post`: Reference to the favourited `Post`
- `createdAt`: Timestamp

### **PasswordResetToken**
- `id`: Primary Key (Long)
- `token`: Unique reset token (String)
- `seller`: Reference to the `Seller`
- `expiryDate`: Timestamp after which token is invalid

### **WhatsAppLoginSession** (table: `whatsapp_login_sessions`)
- `id`: Primary Key (UUID)
- `sessionId`: UUID shared with the frontend for polling (unique)
- `loginToken`: UUID embedded in the QR code / wa.me deep link (unique)
- `claimToken`: UUID issued on bot confirmation, exchanged once for a JWT (unique, nullable)
- `status`: Enum — `PENDING` → `COMPLETED` → `CLAIMED`; or `EXPIRED` / `PHONE_NOT_LINKED`
- `sellerId`: FK to `sellers.sellerId`
- `phoneNumber`: Raw phone number received from bot
- `creatorIp`: IP address of the browser that created the session
- `createdAt`: Session creation timestamp
- `expiresAt`: `createdAt + 5 minutes`
- `completedAt`: Timestamp when bot confirmed the login
- `claimedAt`: Timestamp when frontend exchanged claimToken for JWT

---

## 5. Frontend Architecture

The frontend is a **Progressive Web App (PWA)** built for speed and simplicity.

- **`index.html`**: Uses **Pico.css** for a native-feeling mobile UI. **Alpine.js** handles the reactive state.
- **`js/app.js`**: Client-side logic for API communication, SPA navigation, and interactive features.
- **`css/custom.css`**: Supplementary styling overrides.

**Key Features:**
- **Listings Feed:** Interactive grid with category filters, search, and image carousels.
- **Favourites System:** Real-time sync with backend to track saved items.
- **Create Listing:** Multi-image upload with drag-and-drop, previews, and reordering.
- **Profile Management:** Listing management (mark sold, delete) and account settings.
- **Auth Flows:** Traditional Login/Register, WhatsApp QR Login, and Password Reset.

---

## 6. Integrations & Special Features

### **Security & University Verification**
- Access and registrations enforce `@constructor.university` email domains.
- Authentication relies on **JWT** Bearer tokens.
- **Email Service:** Used for sending password reset links and other notifications.

### **WhatsApp Bot Bridge**
- **Bot Engine:** Built with `whatsapp-web.js`. Handles group message parsing and interactive commands.
- **AI Analysis:** Uses **Google Gemini** (`gemini-2.5-flash-lite`, with automatic fallback to `gemini-2.0-flash-lite` → `gemini-1.5-flash-8b` → `gemini-1.5-flash`) for intent classification and data extraction from natural language.
- **Local Model (Benchmark only):** `Gemma4Service.js` calls a locally running **Gemma 4** model via [Ollama](https://ollama.com) for benchmarking against Gemini. It is not part of the production bot flow and requires no setup from teammates.
- **LLM Observability:** [Langfuse](https://langfuse.com) traces every LLM call (latency, token usage, errors) across both Gemini and Gemma.
- **Webhook Service:** Dispatches parsed data to the backend via secure endpoints.
- **Cloudinary Integration:** Both bot and backend use Cloudinary for optimized image hosting.

### **WhatsApp QR Login**
- A seamless, passwordless login flow where users scan a QR code on the website using WhatsApp to authenticate.
- Relies on a shared secret (`BOT_API_KEY`) for secure communication between the Bot and Backend.

### **Testing**
- **Unit tests:** High coverage for critical services using MockK.
- **Integration tests:** Full-stack validation using MockMvc and Testcontainers (PostgreSQL).
