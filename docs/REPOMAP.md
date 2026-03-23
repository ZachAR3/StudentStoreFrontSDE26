# Repository Map & API Documentation

This document provides a human-readable overview of the Student Storefront project, its file structure, and its technical interfaces.

## 1. Project Overview
A campus-specific marketplace designed to move student sales from chaotic WhatsApp groups into a structured, verified web platform.

**Tech Stack:**
- **Backend:** Spring Boot (Kotlin), Spring Data JPA, PostgreSQL.
- **Frontend:** Alpine.js, Pico.css (served as a PWA from static resources).
- **Integration:** Node.js (WhatsApp Bot Bridge), Gemini API (parsing).

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
│   │   │   ├── config/        # Security, System, and Data Loading (DataLoader)
│   │   │   ├── controller/    # REST Endpoints and Global Error Handling
│   │   │   ├── dto/           # Data Transfer Objects (Request/Response/Update)
│   │   │   ├── entity/        # JPA Entities (Post, Seller)
│   │   │   ├── repository/    # Database Access Layers
│   │   │   └── service/       # Business Logic
│   │   └── resources/
│   │       ├── static/        # Frontend (index.html, js/app.js)
│   │       └── application.properties # Database & App config
│   └── test/                  # Automated tests
└── docker-compose.yaml        # Local PostgreSQL setup
```

---

## 3. API Specification (REST)

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
| `GET` | `/api/posts/category/{name}` | Filter posts by category |
| `GET` | `/api/posts/seller/{id}` | Get all posts by a specific seller |

#### **Data Formats (JSON)**

**Post Request (`POST /api/posts`):**
```json
{
  "title": "Calculus Textbook",
  "price": 45.00,
  "description": "Like new condition",
  "imageUrl": "https://...",
  "category": "Books",
  "sellerId": 1
}
```

**Post Response:**
```json
{
  "postId": 101,
  "title": "Calculus Textbook",
  "price": 45.00,
  "description": "...",
  "imageUrl": "...",
  "category": "Books",
  "isSold": false,
  "createdAt": "2026-03-23T14:00:00",
  "seller": {
    "sellerId": 1,
    "name": "John Doe",
    "email": "j.doe@constructor.university"
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
- `imageUrl`: Link to item photo (String)
- `category`: Classification (e.g., Books, Electronics)
- `isSold`: Boolean status flag
- `seller`: Reference to a `Seller`

### **Seller**
- `sellerId`: Primary Key (Long)
- `name`: Full name
- `email`: Verification email (@constructor.university)
- `phoneNumber`: WhatsApp contact info

---

## 5. Frontend Architecture

The frontend is a **Progressive Web App (PWA)** built for speed and simplicity.

- **`index.html`**: Uses **Pico.css** for a native-feeling mobile UI. **Alpine.js** handles the reactive state (listing grid, form visibility).
- **`js/app.js`**:
    - `fetchPosts()`: Communicates with the Spring Boot backend.
    - `createPost()`: Handles form submission and local state updates.
    - `storefrontData()`: The global Alpine component managing `posts`, `isLoading`, and `errorMessage`.

---

## 6. Integrations & Special Features

### **WhatsApp Bot Bridge**
- **Trigger:** Users forward messages to a dedicated Node.js bot.
- **Action:** The bot uses regex or Gemini to parse the price, title, and image, then calls `POST /api/posts`.
- **Magic Links:** The backend generates short-lived JWTs sent to the user via WhatsApp for passwordless login to their seller dashboard.

### **University Verification**
- Access and "Verified" badges are restricted to `@constructor.university` email domains to ensure a high-trust local community.
