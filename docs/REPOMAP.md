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
│   ├── frontend-modularity-implementation-plan.md # Frontend refactor/build plan
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
│   │       ├── static/
│   │       │   ├── config/layouts/ # Static layout JSON definitions used by the frontend runtime
│   │       │   ├── css/       # Split frontend stylesheets (tokens, shell, layout system, elements, forms, profile, builder)
│   │       │   ├── js/
│   │       │   │   ├── core/  # Namespace, storage, router, API client, registries, validators, runtime, adapters
│   │       │   │   ├── services/ # Thin REST contract modules around frontend/backend endpoints
│   │       │   │   ├── stores/ # Domain state modules (auth, marketplace, favourites, profile, upload, builder)
│   │       │   │   ├── elements/ # Element definitions for layout-driven rendering
│   │       │   │   ├── builder/ # Builder helpers (palette, drag-drop, inspector, preview)
│   │       │   │   └── data/  # Categories, default layouts, sample data
│   │       │   ├── index.html # SPA shell and Alpine templates
│   │       │   └── js/app.js  # Frontend composition entrypoint
│   │       └── application.properties # Database, JWT & WhatsApp bot config
│   └── test/
│       └── kotlin/com/studentstorefront/
│           ├── service/       # Unit tests (WhatsAppQrLoginServiceTest — MockK)
│           └── controller/    # Integration tests (WhatsAppQrLoginControllerTest, PostSearchControllerTest — MockMvc + Testcontainers)
├── bot/                       # Node.js WhatsApp Bot integration
│   ├── src/
│   │   ├── WhatsappBot.js     # Main bot script: group listing capture, consent flow, QR login, shopping chat delegation
│   │   ├── shopping/
│   │   │   └── WhatsAppShoppingChat.js # DM shopping commands, listing pagination, reaction navigation, seller contact links
│   │   ├── services/
│   │   │   ├── llm/
│   │   │   │   ├── modelRouter.js # Provider router with primary+fallback chain for runtime LLM selection
│   │   │   │   └── providers/
│   │   │   │       ├── geminiProvider.js # Gemini provider adapter (prompt + parse/classify + model fallback)
│   │   │   │       └── openaiCompatibleProvider.js # OpenAI-style adapter for LiteLLM/self-hosted endpoints
│   │   │   ├── langfuseService.js    # Shared Langfuse client for LLM observability
│   │   │   ├── springServices.js     # Spring Boot REST API calls for listing creation, lookup, search, and WhatsApp login
│   │   │   ├── webhookService.js     # Express webhook server (seller-registered)
│   │   │   ├── claudinary.js         # Cloudinary image upload helper
│   │   │   └── Prompt-File.js        # Prompt templates (classification, parsing, image description)
│   │   └── tests/
│   │       ├── LLM_Services_For_Testing/
│   │       │   ├── botGeminiService.js   # Legacy Gemini-only service (kept for compatibility/tests)
│   │       │   └── Gemma4Service.js      # Gemma via LiteLLM (benchmark/testing only)
│   │       ├── GeminiAPI-test-Mock.js  # Standalone test harness with mocked services
│   │       ├── runBenchmark.js         # Text benchmark: Gemini vs Gemma vs ChatGPT
│   │       └── Benchmark_Images.js     # Image description benchmark: Gemma vs ChatGPT vs Gemini
│   ├── .env.example           # Bot environment template (provider routing, LiteLLM/OpenAI-compatible vars)
│   └── package.json           # Bot dependencies (includes `openai` SDK for OpenAI-compatible provider)
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
| `GET` | `/api/posts/search?q={text}&category={name}` | Public | Search unsold listings by title/description with optional category filter; supports pageable `page`, `size`, and `sort` params |
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

### **Favourite Management** (`/api/favourites`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `GET` | `/api/favourites` | Seller/Admin | Return the authenticated user's favourite post ids |
| `POST` | `/api/favourites/{postId}` | Seller/Admin | Add a post to the authenticated user's favourites |
| `DELETE` | `/api/favourites/{postId}` | Seller/Admin | Remove a post from the authenticated user's favourites |

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
- `seller`: Reference to the owning `Seller`
- `post`: Reference to the favourited `Post`
- Uniqueness is enforced per seller/post pair at the service layer and repository level

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

- **`index.html`**: The SPA shell. It loads ordered browser scripts, hosts Alpine templates, and renders the live app plus the front-end-only layout builder.
- **`js/app.js`**: The composition entrypoint. It wires domain stores together, exposes Alpine actions/getters, manages SPA navigation, and bridges the layout runtime to the rendered views.
- **`js/core/*`**: The front-end framework layer.
  - `namespace.js` creates `window.Storefront`.
  - `storage.js` wraps localStorage reads/writes.
  - `router.js` defines view routes.
  - `api-client.js` centralizes fetch/auth header behavior.
  - `content-adapters.js` maps backend DTOs into generic catalog/menu items.
  - `validators.js`, `element-registry.js`, `layout-registry.js`, and `layout-runtime.js` power the typed layout system.
- **`js/stores/*`**: Domain-focused state modules for auth, marketplace, favourites, profile, uploads, and the layout builder.
- **`js/services/*`**: Thin REST contract modules for auth, WhatsApp login, posts, sellers, and favourites. Endpoint strings live here instead of the root app controller.
- **`js/elements/*`**: Registered element definitions such as `marketplace.filterBar`, `catalog.grid`, `marketplace.itemCard`, `profile.summary`, `profile.listingList`, `restaurant.menuHero`, and `restaurant.menuItemCard`.
- **`js/builder/*`**: Helper modules for the layout builder experience: palette grouping, drag/drop movement, inspector prop updates, and preview width presets.
- **`js/data/*`**: Static categories, sample marketplace/menu data, and the default layout URL manifest/loader.
- **`config/layouts/*.json`**: Versioned layout JSON files for marketplace home, favourites, profile, and the sample restaurant menu. These files are the runtime source of truth loaded by the layout registry.
- **`css/custom.css`**: Aggregates the split stylesheet set.
- **`css/tokens.css`**: Shared design tokens for spacing, radii, shadows, control sizing, and layout values.
- **`css/shell.css`**: Header, shell, and top-level page spacing.
- **`css/layout-system.css`**: Region/layout primitives, banners, empty/loading states.
- **`css/elements.css`**: Card, carousel, contact-action, and restaurant-specific presentation styles.
- **`css/forms.css`**: Auth and create-listing form styling, password meters, upload UI.
- **`css/profile.css`**: Profile header, listing rows, danger zone, and modal presentation.
- **`css/layout-builder.css`**: Builder canvas, palette, preview, direct-manipulation affordances, and inspector styling.

### Layout Runtime

The current frontend is no longer a single hard-coded listings page. Marketplace and sample restaurant pages are assembled from typed elements and versioned layout definitions:

- **Element registry**: Each element declares a `type`, prop defaults, editor controls, and supported rendering metadata.
- **Layout registry**: Stores default route layouts such as `marketplace.home` and `restaurant.menu.sample`.
- **Layout runtime**: Resolves a route into regions/elements, validates layout safety, merges props with defaults, and binds allowed data sources.
- **Content adapters**: Keep the generic layout system independent from Spring DTO details by converting posts into reusable catalog items.

### Layout Builder

There is now a front-end-only `layoutBuilder` SPA view for editing layouts without backend persistence:

- Elements can be added from a palette, selected directly in the preview, reordered, duplicated, and deleted.
- Drafts persist in `localStorage` under `storefront.layoutBuilderDraft.v1`.
- Created sites persist in `localStorage` under `storefront.createdSites.v1`.
- The builder can validate layouts, import/export JSON, preview at multiple widths, create saved sites, and apply updated layouts in-browser.
- Desktop preview mode uses a wide focused canvas; mobile/tablet/free-width modes constrain the preview frame to device-like widths.
- Large composed elements such as `catalog.grid`, `restaurant.menuGrid`, `profile.summary`, and `profile.listingList` span the full row inside responsive regions so nested grids do not collapse into narrow side columns.
- The restaurant sample exists to demonstrate how future subapps can reuse the same runtime without immediate backend changes.

### Created Sites

Created sites are local browser artifacts, not backend records. A created site stores a cloned layout with an id, name, context, timestamps, and route `createdSitePreview`. Users can create a site from the builder, view it from the Created Sites page, reopen it in the builder, duplicate it, or delete it. Viewing a created site uses the same layout runtime and sample data adapters as the builder preview.

**Views (SPA):**
- **Listings** — Homepage grid of item cards with category/sort filters and search. Uses the layout runtime plus Alpine.js image carousel behavior for multi-photo listings. Each card features quick-contact links (Email & pre-filled WhatsApp `wa.me` links).
- **Favourites** — Grid view of saved items, fully synced with the backend via `FavouriteController`. Accessible via the header icon.
- **Profile** — Displays seller info (avatar, name, email, phone, listing count) and their posted listings. Own-profile includes listing management (mark sold, delete) and a "Danger Zone" for account deletion with password-verified confirmation modal.
- **Login / Register / WhatsApp Login** — Authentication flows.
- **Create Listing** — Authenticated form for posting new items. Features a drag-and-drop upload zone supporting up to 10 local images with live previews and drag-to-reorder functionality. Uploads are handled securely via the Spring Boot backend to Cloudinary.
- **Layout Builder** — Front-end-only layout editing workspace with palette, responsive preview, validation, JSON import/export, and draft persistence.
- **Created Sites** — Local site library for viewing, editing, duplicating, and deleting layouts created from the builder.
- **Created Site Preview** — Runtime-rendered view of a selected locally created site.
- **Restaurant Preview** — Sample restaurant/menu experience rendered through the same layout system using static sample data.

---

## 6. Integrations & Special Features

### **Security & University Verification**
- Access and registrations enforce `@constructor.university` email domains.
- Authentication relies on **JWT** Bearer tokens.
- **Email Service:** Used for sending password reset links and other notifications.

### **WhatsApp Bot Bridge**
- **Bot Engine:** Built with `whatsapp-web.js`. Handles group message parsing, DM commands, QR login confirmations, and reaction events.
- **Command Short-Circuiting:** Known bot commands are detected before listing classification in the target chat, so command messages skip LLM classification entirely (lower latency and token usage).
- **Text-Chat Shopping:** `WhatsAppShoppingChat.js` lets buyers browse the store in WhatsApp DMs with commands like `shop`, `recent`, `search desk`, `category electronics`, `details 1`, and numeric replies for seller links. Results are sent in configurable pages (`SHOPPING_PAGE_SIZE`, default `5`) and support `⬅️` / `➡️` reaction pagination.
- **Multi-Model LLM Routing:** Runtime classification/parsing now goes through `services/llm/modelRouter.js` with env-configured primary provider (`LLM_PRIMARY_PROVIDER`) and fallback providers (`LLM_FALLBACK_PROVIDERS`), designed for adding more providers later.
- **Gemini Provider:** Includes model-level fallback chain (`gemini-2.5-flash-lite` → `gemini-2.0-flash-lite` → `gemini-1.5-flash-8b` → `gemini-1.5-flash`).
- **OpenAI-Compatible Provider:** Uses the official `openai` JS SDK against OpenAI-style endpoints (for example LiteLLM on a self-hosted domain).
- **Local Model (Benchmark only):** `Gemma4Service.js` calls a locally running **Gemma 4** model via [Ollama](https://ollama.com) for benchmarking against Gemini. It is not part of the production bot flow and requires no setup from teammates.
- **LLM Observability:** [Langfuse](https://langfuse.com) traces every LLM call (latency, token usage, errors) across configured providers.
- **Spring Service Wrapper:** `springServices.js` dispatches parsed listings to secure bot endpoints and reads public listing/search APIs for shopping chat.
- **Webhook Service:** Receives seller registration callbacks so pending bot-created listings can continue after signup.
- **Cloudinary Integration:** Both bot and backend use Cloudinary for optimized image hosting.

### **WhatsApp QR Login**
- A seamless, passwordless login flow where users scan a QR code on the website using WhatsApp to authenticate.
- Relies on a shared secret (`BOT_API_KEY`) for secure communication between the Bot and Backend.

### **Testing**
- **Unit tests:** High coverage for critical services using MockK.
- **Integration tests:** Full-stack validation using MockMvc and Testcontainers (PostgreSQL).
