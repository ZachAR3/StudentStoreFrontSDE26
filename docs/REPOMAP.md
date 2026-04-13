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
│   │   │   ├── controller/    # REST Endpoints (Auth, Post, Seller)
│   │   │   ├── dto/           # Data Transfer Objects (request, response, update)
│   │   │   ├── entity/        # JPA Entities (Post, PostMedia, Seller)
│   │   │   ├── enums/         # Enums (Category, Role)
│   │   │   ├── exception/     # Global Exception Handling
│   │   │   ├── repository/    # Database Access Layers (Post, PostMedia, Seller)
│   │   │   └── service/       # Business Logic (JwtService, UserDetailsService, PostService, SellerService)
│   │   └── resources/
│   │       ├── static/        # Frontend (index.html, css/custom.css, js/app.js)
│   │       └── application.properties # Database & App config
│   └── test/                  # Automated tests
├── bot/                       # Node.js WhatsApp Bot integration
│   ├── src/
│   │   ├── WhatsappBot.js     # Main bot script with user consent logic
│   │   └── services/          # Bot services (Gemini parser, Cloudinary upload)
│   └── package.json           # Bot dependencies
└── docker-compose.yaml        # Local PostgreSQL setup
```

---

## 3. API Specification (REST)

### **Authentication** (`/api/auth`)

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `POST` | `/api/auth/register` | Register a new seller and receive a JWT Bearer token |
| `POST` | `/api/auth/login` | Authenticate with email/password and receive a JWT Bearer token |

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
| `PUT` | `/api/sellers/{id}` | Update an existing seller |
| `DELETE` | `/api/sellers/{id}` | Delete a seller |

#### **Data Formats (JSON)**

**Auth Response:**
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
- Authentication relies on **JSON Web Tokens (JWT)** generated during login or registration to secure backend endpoints. 

### **WhatsApp Bot Bridge**
- **Bot Engine:** The Node.js application (`whatsapp-web.js`) acts as a bridge.
- **AI Analysis:** Uses **Google Gemini** (`gemini-2.0-flash-lite`) to classify if a group message is a valid listing and to parse structured data from natural language.
- **Image Hosting:** Integrates with **Cloudinary** for storing and serving listing images.
- **Consent Mechanism:** The bot tracks user consent state when users interact with it. Consent is explicitly registered or denied via chat before interactions or uploads to the platform occur.