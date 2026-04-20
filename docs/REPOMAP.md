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
│   │   │   ├── config/        # Security (JWT, Spring Security), OpenApi, System, and DataLoader
│   │   │   ├── controller/    # REST Endpoints (Auth, Post, Seller, WhatsAppQrLogin)
│   │   │   ├── dto/           # Data Transfer Objects (request, response, update)
│   │   │   ├── entity/        # JPA Entities (Post, PostMedia, Seller, WhatsAppLoginSession)
│   │   │   ├── enums/         # Enums (Category, Role, WhatsAppSessionStatus)
│   │   │   ├── exception/     # Global Exception Handling
│   │   │   ├── repository/    # Database Access Layers (Post, PostMedia, Seller, WhatsAppLoginSession)
│   │   │   └── service/       # Business Logic (JwtService, UserDetailsService, PostService, SellerService, WhatsAppQrLoginService)
│   │   └── resources/
│   │       ├── static/        # Frontend (index.html, css/custom.css, js/app.js)
│   │       └── application.properties # Database, JWT & WhatsApp bot config
│   └── test/
│       └── kotlin/com/studentstorefront/
│           ├── service/       # Unit tests (WhatsAppQrLoginServiceTest — MockK, no Spring context)
│           └── controller/    # Integration tests (WhatsAppQrLoginControllerTest — MockMvc + Testcontainers)
├── bot/                       # Node.js WhatsApp Bot integration
│   ├── src/
│   │   ├── WhatsappBot.js     # Main bot script: consent flow + QR login handler
│   │   └── services/          # springServices.js, botGeminiService.js, claudinary.js
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
| `POST` | `/api/auth/whatsapp/session` | Public | Create a WhatsApp QR login session; returns `sessionId`, `qrContent` (wa.me deep link), and `expiresAt` |
| `GET` | `/api/auth/whatsapp/session/{sessionId}` | Public | Poll session status; returns `PENDING`, `COMPLETED` (with `claimToken`), `EXPIRED`, `PHONE_NOT_LINKED`, or `CLAIMED` |
| `POST` | `/api/auth/whatsapp/confirm` | `X-Bot-Api-Key` header | Bot-only: confirm a login by supplying `loginToken` + `phoneNumber`; transitions session to `COMPLETED` and generates a `claimToken` |
| `POST` | `/api/auth/whatsapp/claim` | Public | Exchange a one-time `claimToken` for a JWT; returns 410 Gone if already used or expired |

### **Post Management** (`/api/posts`)

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/posts` | Fetch paginated list of all posts (default size=20) |
| `POST` | `/api/posts` | Create a new listing |
| `GET` | `/api/posts/{id}` | Get details of a specific post |
| `PUT` | `/api/posts/{id}` | Update an existing post |
| `PATCH` | `/api/posts/{id}/mark-sold` | Mark a post as sold (removes from active feed) |
| `DELETE` | `/api/posts/{id}` | Delete a post |
| `GET` | `/api/posts/available` | Get only unsold listings |
| `GET` | `/api/posts/category/{name}` | Filter posts by category (e.g., ELECTRONICS, BOOKS) |
| `GET` | `/api/posts/seller/{id}` | Get all posts by a specific seller |

### **Seller Management** (`/api/sellers`)

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/sellers` | Fetch paginated list of all sellers (default size=20) |
| `POST` | `/api/sellers` | Create a new seller directly (bypassing auth/register if admin) |
| `GET` | `/api/sellers/{id}` | Get details of a specific seller |
| `GET` | `/api/sellers/email/{email}`| Get details of a specific seller by email |
| `GET` | `/api/sellers/by-phone` | Bot-specific: Lookup seller by phone number |
| `PUT` | `/api/sellers/{id}` | Update an existing seller |
| `DELETE` | `/api/sellers/{id}` | Delete a seller |

#### **Data Formats (JSON)**

**WhatsApp Session Response (`POST /api/auth/whatsapp/session`):**
```json
{
  "sessionId": "a1b2c3d4-...",
  "qrContent": "https://wa.me/15551234567?text=login%3Af7e8d9c0-...",
  "expiresAt": "2026-04-21T14:35:00"
}
```

**WhatsApp Poll Response (`GET /api/auth/whatsapp/session/{sessionId}`):**
```json
{ "status": "PENDING",    "claimToken": null }
{ "status": "COMPLETED",  "claimToken": "x9y8z7w6-..." }
{ "status": "EXPIRED",    "claimToken": null }
```

**WhatsApp Confirm Request (`POST /api/auth/whatsapp/confirm`) — bot only:**
```json
{ "loginToken": "f7e8d9c0-...", "phoneNumber": "15559876543" }
```

**WhatsApp Claim Request (`POST /api/auth/whatsapp/claim`):**
```json
{ "claimToken": "x9y8z7w6-..." }
```

**Auth Response (login, register, and claim all return this shape):**
```json
{
  "token": "eyJhbGciOiJIUz...",
  "type": "Bearer",
  "seller": {
    "sellerId": 1,
    "name": "John Doe",
    "email": "j.doe@constructor.university",
    "phoneNumber": "+491234567890"
  }
}
```

**Post Request (`POST /api/posts`):**
```json
{
  "title": "Calculus Textbook",
  "price": 45.00,
  "description": "Like new condition",
  "imageUrlList": ["https://res.cloudinary.com/..."],
  "category": "BOOKS",
  "sellerId": 1,
  "expiresAt": "2026-05-01T14:00:00"
}
```

**Post Response:**
```json
{
  "postId": 1,
  "title": "Calculus Textbook",
  "price": 45.00,
  "mediaUrls": ["https://res.cloudinary.com/..."],
  "description": "Like new condition",
  "category": "BOOKS",
  "isSold": false,
  "createdAt": "2026-03-23T14:00:00",
  "expiresAt": "2026-05-01T14:00:00",
  "seller": {
    "sellerId": 1,
    "name": "John Doe",
    "email": "j.doe@constructor.university",
    "phoneNumber": "+491234567890"
  }
}
```

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

### **WhatsAppLoginSession** (table: `whatsapp_login_sessions`)
- `id`: Primary Key (UUID)
- `sessionId`: UUID shared with the frontend for polling (unique)
- `loginToken`: UUID embedded in the QR code / wa.me deep link (unique)
- `claimToken`: UUID issued on bot confirmation, exchanged once for a JWT (unique, nullable)
- `status`: Enum — `PENDING` → `COMPLETED` → `CLAIMED`; or `EXPIRED` / `PHONE_NOT_LINKED`
- `sellerId`: FK to `sellers.sellerId` (populated when bot confirms a matching phone)
- `phoneNumber`: Raw phone number received from bot (audit log)
- `creatorIp`: IP address of the browser that created the session
- `createdAt`: Session creation timestamp
- `expiresAt`: `createdAt + 5 minutes`; session rejects confirmation after this
- `completedAt`: Timestamp when bot confirmed the login
- `claimedAt`: Timestamp when frontend exchanged claimToken for JWT (terminal state)

---

## 5. Frontend Architecture

The frontend is a **Progressive Web App (PWA)** built for speed and simplicity.

- **`index.html`**: Uses **Pico.css** for a native-feeling mobile UI. **Alpine.js** handles the reactive state (listing grid, form visibility).
- **`js/app.js`**: Contains client-side logic to communicate with the Spring Boot backend REST API.
- **`css/custom.css`**: Supplementary styling overrides.

---

## 6. Integrations & Special Features

### **Security & University Verification**
- Access and registrations enforce `@constructor.university` email domains to ensure a high-trust local community.
- Authentication relies on **JSON Web Tokens (JWT)** generated during login, registration, or WhatsApp QR login to secure backend endpoints.

### **WhatsApp Bot Bridge**
- **Bot Engine:** The Node.js application (`whatsapp-web.js`) acts as a bridge.
- **AI Analysis:** Uses **Google Gemini** (`gemini-2.0-flash-lite`) to classify if a group message is a valid listing and to parse structured data from natural language.
- **Image Hosting:** Integrates with **Cloudinary** for storing and serving listing images.
- **Consent Mechanism:** The bot tracks user consent state when users interact with it. Consent is explicitly registered or denied via chat before interactions or uploads to the platform occur.

### **WhatsApp QR Login**
- **Flow:** Frontend calls `POST /session` → receives a `wa.me` deep link rendered as a QR code → user scans with phone → WhatsApp pre-fills `login:<token>` to the bot → bot calls `POST /confirm` with the token and sender's phone → backend looks up the Seller, issues a `claimToken`, marks session `COMPLETED` → frontend polls until `COMPLETED` → calls `POST /claim` with the `claimToken` → receives JWT.
- **Session TTL:** 5 minutes from creation. `claimToken` TTL: 5 minutes from bot confirmation. Both are single-use.
- **Bot authentication:** `POST /confirm` is guarded by `X-Bot-Api-Key` header (shared secret).
- **Cleanup:** `@Scheduled` job runs every 5 minutes to expire stale `PENDING` sessions.
- **Required env vars:** `WHATSAPP_BOT_PHONE` (bot number without `+`), `BOT_API_KEY` (set on both backend and bot).

### **Testing**
- **Unit tests** (`WhatsAppQrLoginServiceTest`): 19 tests covering all service branches using MockK — no Spring context loaded.
- **Integration tests** (`WhatsAppQrLoginControllerTest`): 14 tests covering full HTTP stack using MockMvc + Testcontainers (real PostgreSQL). Each test runs in a rolled-back transaction for isolation.
