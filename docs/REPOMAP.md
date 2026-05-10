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
│   │   │   ├── controller/    # REST Endpoints (Auth, Favourite, Post, Review, Seller, WhatsAppQrLogin)
│   │   │   ├── dto/           # Data Transfer Objects (request, response, update)
│   │   │   ├── entity/        # JPA Entities (Favourite, PasswordResetToken, Post, PostMedia, Review, Seller, WhatsAppLoginSession)
│   │   │   ├── enums/         # Enums (Category, PostStatus, ReviewDirection, Role, WhatsAppSessionStatus)
│   │   │   ├── exception/     # Global Exception Handling
│   │   │   ├── repository/    # Database Access Layers (Favourite, PasswordResetToken, Post, PostMedia, Review, Seller, WhatsAppLoginSession)
│   │   │   ├── scheduler/     # Listing expiration reminders and archival jobs
│   │   │   └── service/       # Business Logic (BotNotificationService, CloudinaryService, EmailService, FavouriteService, JwtService, PasswordResetService, PostService, ReviewService, SellerService, WhatsAppQrLoginService)
│   │   └── resources/
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
│           ├── service/       # Unit tests (WhatsAppQrLoginServiceTest — MockK)
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
│   │   │   ├── webhookService.js     # Express webhook server (seller-registered and outbound send-message bridge)
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
| `POST` | `/api/posts/bot` | `X-Bot-Api-Key` | Bot-only: Create one marketplace listing from parsed WhatsApp output, including reusable image hashes |
| `POST` | `/api/posts/bot/media/resolve` | `X-Bot-Api-Key` | Bot-only: Resolve known media URLs by SHA-256 image hash so duplicate image bytes can be reused |
| `POST` | `/api/posts/bot/renew/{id}` | `X-Bot-Api-Key` | Bot-only: Renew an archived/expired post, set it `ACTIVE`, extend `expiresAt`, and clear reminder state |
| `GET` | `/api/posts/{id}` | Public | Get details of a specific post |
| `PUT` | `/api/posts/{id}` | SELLER/ADMIN | Update an existing post |
| `PATCH` | `/api/posts/{id}/mark-sold`| SELLER/ADMIN | Mark a post as sold with a required registered `buyerId`, store sale metadata, and request a buyer review |
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
| `GET` | `/api/sellers/search?q={text}` | SELLER/ADMIN | Search enabled registered sellers by name/email for buyer selection when marking a listing sold |
| `PUT` | `/api/sellers/{id}` | SELLER/ADMIN | Update an existing seller |
| `DELETE` | `/api/sellers/me` | SELLER/ADMIN | Self-service account deletion (requires password re-entry) |
| `DELETE` | `/api/sellers/{id}` | ADMIN | Delete a seller |

### **Review Management** (`/api/reviews`)

| Method | Endpoint | Auth | Description |
| :--- | :--- | :--- | :--- |
| `POST` | `/api/reviews` | SELLER/ADMIN | Submit one transaction review for the authenticated user; backend derives buyer-to-seller or seller-to-buyer direction from the sold post |
| `GET` | `/api/reviews/context/{postId}` | SELLER/ADMIN | Return review labels/context for a sold transaction, including whether the authenticated user already reviewed it |
| `GET` | `/api/reviews/pending` | SELLER/ADMIN | Return sold transactions where the authenticated user can still leave a review |
| `GET` | `/api/reviews/profile/{sellerId}` | SELLER/ADMIN | Return seller-rating summary, buyer-rating summary, and recent reviews for a profile |

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
- `expiresAt`: Optional expiration timestamp; new posts default to two days from creation
- `status`: Lifecycle Enum (`ACTIVE`, `ARCHIVED`). Public/search queries use `ACTIVE`; expired posts are archived, not hard-deleted.
- `reminderSentAt`: Timestamp marking that the pre-expiration WhatsApp reminder was sent
- `soldAt`: Timestamp when the seller recorded the sale
- `seller`: Reference to a `Seller`
- `buyer`: Optional reference to the registered `Seller` account that purchased the item
- `postMedia`: Collection of `PostMedia` objects

### **PostMedia**
- `id`: Primary Key (Long)
- `mediaUrl`: URL to the hosted image (String)
- `imageHash`: Optional SHA-256 hash of the original image bytes, used by the bot/backend to reuse stored URLs for duplicate images
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

- **`index.html`**: The SPA shell. It loads ordered browser scripts, hosts Alpine templates, and renders the simplified title bar, persistent site sidebar, live app views, and the front-end-only layout builder.
- **`js/app.js`**: The composition entrypoint. It wires domain stores together, exposes Alpine actions/getters, manages SPA navigation and browser history/hash state, and bridges the layout runtime to the rendered views.
- **`js/core/*`**: The front-end framework layer.
  - `namespace.js` creates `window.Storefront`.
  - `storage.js` wraps localStorage reads/writes.
  - `router.js` defines view routes.
  - `api-client.js` centralizes fetch/auth header behavior.
  - `content-adapters.js` maps backend DTOs into generic catalog/menu items.
  - `validators.js`, `element-registry.js`, `layout-registry.js`, and `layout-runtime.js` power the typed layout system.
- **`js/stores/*`**: Domain-focused state modules for auth, marketplace, favourites, profile, uploads, cart selection, and the layout builder.
- **`js/services/*`**: Thin REST contract modules for auth, WhatsApp login, posts, sellers, reviews, and favourites. Endpoint strings live here instead of the root app controller.
- **`js/elements/*`**: Registered element definitions such as `marketplace.filterBar`, `catalog.grid`, `marketplace.itemCard`, `profile.summary`, `profile.listingList`, `restaurant.menuHero`, `restaurant.menuItemCard`, and reusable sales-site sections (`common.salesHero`, `common.featureStrip`, `common.contactPanel`, `common.announcementBar`).
- **`js/builder/*`**: Helper modules for the layout builder experience: palette grouping, drag/drop movement, inspector prop updates, and preview width presets.
- **`js/data/*`**: Static categories, sample marketplace/menu data, and the default layout URL manifest/loader.
- **`config/layouts/*.json`**: Versioned layout JSON files for marketplace home, favourites, profile, and the sample restaurant menu. These files are the runtime source of truth loaded by the layout registry.
- **`css/custom.css`**: Aggregates the split stylesheet set.
- **`css/tokens.css`**: Campus Editorial theme tokens, PicoCSS overrides, spacing, radii, shadows, control sizing, and layout values. See `docs/THEMEMAP.md`.
- **`css/shell.css`**: Header, persistent left site navigation, support page/footer styling, responsive mobile shell, and top-level page spacing.
- **`css/layout-system.css`**: Region/layout primitives, responsive card-grid constraints, banners, empty/loading states, and listing-detail presentation.
- **`css/elements.css`**: Marketplace listing cards, compact contact icon actions, carousels, mobile filter/card behavior, selected-cart UI, restaurant-specific presentation styles, and reusable sales-site sections.
- **`css/forms.css`**: Auth and create-listing form styling, mobile form action stacking, password meters, upload UI.
- **`css/profile.css`**: Profile header, rating summary pills, pending/recent review cards, listing rows, danger zone, buyer-search controls, and responsive modal presentation.
- **`css/layout-builder.css`**: Builder canvas, palette, preview shell, mobile-safe toolbars, direct-manipulation affordances, and inspector styling.

### Layout Runtime

The current frontend is no longer a single hard-coded listings page. Marketplace and sample restaurant pages are assembled from typed elements and versioned layout definitions:

- **Element registry**: Each element declares a `type`, prop defaults, editor controls, and supported rendering metadata.
- **Layout registry**: Stores default route layouts such as `marketplace.home` and `restaurant.menu.sample`.
- **Layout runtime**: Resolves a route into regions/elements, validates layout safety, maps layout spacing tokens into CSS variables, merges props with defaults, binds allowed data sources, and applies the global header search to catalog-like data sources (marketplace, favourites, profile listings, restaurant menu, and created-site sample catalogs).
- **Content adapters**: Keep the generic layout system independent from Spring DTO details by converting posts into reusable catalog items.
- **Full-width element handling**: Large composed sections such as catalog grids, profile summaries, restaurant headers, sales heroes, feature strips, contact panels, announcement bars, and empty states span the full row in responsive regions so headers and banners do not collapse into narrow grid columns.
- **Card width constraints**: Marketplace cards keep the flexible `auto-fit` grid but cap individual card width on desktop (`--grid-max-item-width`, default `22rem`) so a single listing or favourite does not stretch across the entire content column. The cap is removed on narrow mobile screens so cards use the available phone width.
- **Mobile shell rendering**: Under `880px`, the shell keeps brand/actions in a compact top row and search in a second full-width row. Under `560px`, action groups remain single-line horizontally scrollable controls instead of expanding the header into tall wrapped rows.

### Layout Builder

There is now a front-end-only `layoutBuilder` SPA view for editing layouts without backend persistence:

- Elements can be added from a palette, selected directly in the preview, reordered, duplicated, and deleted.
- Drafts persist in `localStorage` under `storefront.layoutBuilderDraft.v1`.
- Created sites persist in `localStorage` under `storefront.createdSites.v1`.
- The builder can validate layouts, import/export JSON, preview at multiple widths, create saved sites, and apply updated layouts in-browser.
- Desktop preview mode keeps the palette and inspector visible while fitting the site preview to the available canvas; mobile/tablet/free-width modes use device-like target widths that shrink natively when space is tighter. The preview frame uses layout-aware sizing so zoomed content does not get cropped or force horizontal scrolling, and the builder toolbars collapse into mobile-safe groupings in portrait layouts.
- The palette includes marketplace, profile, restaurant sample, and reusable sales-site sections (`Sales Hero`, `Feature Strip`, `Contact Panel`, `Announcement Bar`) for common sales pages.
- The restaurant sample is treated as a preconfigured builder template rather than a global title-bar shortcut, demonstrating how future subapps can reuse the same runtime without immediate backend changes.

### Created Sites

Created sites are local browser artifacts, not backend records. A created site stores a cloned layout with an id, name, context, timestamps, and route `createdSitePreview`. Users can create a site from the builder, open it from the persistent left site sidebar, reopen it in the builder, duplicate it, or delete it from the local library view. Viewing a created site uses the same layout runtime and sample data adapters as the builder preview.

The left sidebar replaces the old title-bar Created Sites shortcut. It shows core destinations (Marketplace, Saved Items, Layout Builder, Support) plus a YouTube-subscriptions-style list of locally created sites. The sidebar becomes a compact horizontal nav on mobile, with horizontal scrolling preserved for long site names instead of wrapping into broken chips. Header action groups use the same no-wrap mobile behavior so authenticated actions can scroll horizontally without increasing header height.

### SPA Navigation

The Alpine SPA stores view state in the URL hash (`#view=...`, plus `site=...` for created site previews). `navigateTo` pushes history entries, and `popstate` restores the previous view so browser back/forward works for listing detail, builder, saved items, and created-site preview navigation.

**Views (SPA):**
- **Listings** — Homepage grid of compact item cards with category/sort filters and search. Uses the layout runtime plus Alpine.js image carousel behavior for multi-photo listings. Marketplace cards intentionally show only image, title, price, and square icon contact actions for Email and pre-filled WhatsApp `wa.me` links; opening the card shows full listing detail. The favourite overlay is rendered only for authenticated users so logged-out cards do not reserve or expose a dead control area.
- **Favourites** — Grid view of saved items, fully synced with the backend via `FavouriteController`. Accessible via the header icon. Single-item favourite grids use the same desktop max-card constraint as the main listings grid.
- **Profile** — Displays seller info (avatar, name, email, phone, listing count), seller/buyer rating summaries, recent reviews, and posted listings. Own-profile includes listing management (mark sold with registered buyer selection, review buyer, delete), pending review prompts, and a "Danger Zone" for account deletion with password-verified confirmation modal.
- **Login / Register / WhatsApp Login** — Authentication flows.
- **Create Listing** — Authenticated form for posting new items. Features a drag-and-drop upload zone supporting up to 10 local images with live previews and drag-to-reorder functionality. Uploads are handled securely via the Spring Boot backend to Cloudinary.
- **Layout Builder** — Front-end-only layout editing workspace with palette, responsive preview, validation, JSON import/export, and draft persistence.
- **Created Sites** — Local site library for viewing, editing, duplicating, and deleting layouts created from the builder.
- **Created Site Preview** — Runtime-rendered view of a selected locally created site. Created-site routes use sample/catalog adapters and responsive full-width handling for banners and grids, including the same mobile-safe builder preview sizing rules used in the editor.
- **Restaurant Preview** — Sample restaurant/menu experience rendered through the same layout system using static sample data; primarily reached through the builder template flow.
- **Support** — Static help page reachable from the sidebar and footer, with account/listing/safety guidance and a support email action.
- **Listing Detail** — Full listing view with large image gallery, thumbnails, price, seller contact actions, seller profile link, and full description.

---

## 6. Integrations & Special Features

### **Security & University Verification**
- Access and registrations enforce `@constructor.university` email domains.
- Authentication relies on **JWT** Bearer tokens.
- **Email Service:** Used for verification codes and password reset links. SMTP credentials are environment-driven (`MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`); Gmail requires an app password rather than a passkey or normal account password.

### **Listing Expiration**
- New posts receive a two-day `expiresAt`.
- `PostExpirationScheduler` sends WhatsApp renewal reminders for active posts expiring within one hour, then archives expired active posts every 15 minutes.
- Archived posts are hidden from public/search result endpoints but remain recoverable via the bot renew endpoint.
- `DataLoader` backfills legacy `posts.status IS NULL` rows to `ACTIVE` on startup so older data does not break DTO serialization or expiration queries.

### **Sale Reviews & Ratings**
- Sellers mark listings sold from their own profile by selecting an enabled registered buyer account.
- `PostService.markAsSold` records `buyer`, `soldAt`, and `isSold`, rejects self-sales and already-sold listings, and triggers a WhatsApp review request to the buyer.
- `ReviewService` derives the review direction from the sold post: selected buyer reviews the seller, listing owner reviews the buyer.
- Each sold transaction supports at most two reviews: one `BUYER_TO_SELLER` and one `SELLER_TO_BUYER`.
- Profiles expose separate seller-rating and buyer-rating summaries plus recent written reviews.

### **WhatsApp Bot Bridge**
- **Bot Engine:** Built with `whatsapp-web.js`. Handles group message parsing, DM commands, QR login confirmations, and explicit DM posting flows.
- **Command Short-Circuiting:** Known bot commands are detected before listing classification in the target chat, so command messages skip LLM classification entirely (lower latency and token usage).
- **DM Posting Flow:** Users can start a draft with `post`, send text and photos, and let the inactivity timer submit the draft. Consent and registration retries use persisted bot-side state so drafts survive restarts.
- **Missing-Price Staging:** If the parser can identify items but one or more are missing valid prices, the draft moves into `awaiting-price` for up to 24 hours. The bot DMs an itemized missing-price list (for each missing item) and retries the same staged draft when the seller replies.
- **Text-Chat Shopping:** `WhatsAppShoppingChat.js` lets buyers browse the store in WhatsApp DMs with commands like `shop`, `recent`, `search desk`, `category electronics`, `details 1`, numeric replies for seller links, and `n` / `p` for paging. Item details send the cover image when available.
- **Multi-Model LLM Routing:** Runtime classification/parsing goes through `services/llm/modelRouter.js` with env-configured primary provider (`LLM_PRIMARY_PROVIDER`) and fallback providers (`LLM_FALLBACK_PROVIDERS`).
- **Gemini Provider:** Includes current fallback defaults (`gemini-2.5-flash-lite` → `gemini-2.5-flash`) and remaps older `gemini-1.5-*` env values to current models.
- **OpenAI-Compatible Provider:** Uses the official `openai` JS SDK with the OpenAI chat completions API. Current defaults target `gpt-5.4-nano`.
- **Multi-Item Listing Extraction:** The parsing prompt requests one listing per distinct visible item and returns an array payload with per-item metadata plus optional `imageIndexes`. The bot creates one marketplace post per parsed item and maps each post to item-scoped images when indices are provided.
- **Local Model (Benchmark only):** `Gemma4Service.js` calls a locally running **Gemma 4** model via [Ollama](https://ollama.com) for benchmarking against Gemini. It is not part of the production bot flow and requires no setup from teammates.
- **LLM Observability:** [Langfuse](https://langfuse.com) traces every LLM call (latency, token usage, errors) across configured providers.
- **Image Preprocessing:** Listing images are downscaled to fit inside `960x720` before LLM parsing, preserving aspect ratio while reducing latency and payload size.
- **Image Hash Reuse:** Before uploading to Cloudinary, the bot hashes each WhatsApp image, asks the backend whether that exact image is already known, and reuses the existing stored media URL when possible. Only missing images are uploaded.
- **Spring Service Wrapper:** `springServices.js` dispatches parsed listings to secure bot endpoints and reads public listing/search APIs for shopping chat.
- **Webhook Service:** Receives seller registration callbacks so pending bot-created listings can continue after signup, and exposes `/send-message` for backend-triggered WhatsApp notifications such as review requests.
- **Cloudinary Integration:** Both bot and backend use Cloudinary for optimized image hosting.

### **WhatsApp QR Login**
- A seamless, passwordless login flow where users scan a QR code on the website using WhatsApp to authenticate.
- Relies on a shared secret (`BOT_API_KEY`) for secure communication between the Bot and Backend.

### **Testing**
- **Unit tests:** High coverage for critical services using MockK.
- **Integration tests:** Full-stack validation using MockMvc and Testcontainers (PostgreSQL).
