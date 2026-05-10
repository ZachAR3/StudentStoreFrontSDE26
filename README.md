# Student Storefront

Student Storefront is a campus marketplace built to move informal WhatsApp selling into a structured, verified web platform. The project combines a Kotlin/Spring Boot backend, a static PWA-style frontend served by Spring, and a Node.js WhatsApp bot that helps sellers turn chat messages and images into marketplace listings.

The current codebase supports seller accounts, JWT authentication, listing creation and search, favourites, reviews, Cloudinary-backed media uploads, email-driven account flows, and WhatsApp-based login/listing assistance.

## What This Project Includes

- A Spring Boot 4 backend written in Kotlin
- A frontend served from `src/main/resources/static`
- A PostgreSQL database for application data
- A Node.js WhatsApp bot in [`bot/`](/home/zach/Desktop/productivity/Programming/SDE Sales App/bot)
- Local Docker Compose for the database
- Frontend contract validation scripts

## Tech Stack

- Backend: Kotlin, Spring Boot, Spring Security, Spring Data JPA, PostgreSQL
- Frontend: HTML, Alpine.js-style modular static JS, Pico.css-based styling
- Bot: Node.js, `whatsapp-web.js`, OpenAI-compatible and Gemini LLM providers
- Media: Cloudinary
- Docs/API: Springdoc OpenAPI / Swagger UI

## Repository Layout

- [`src/main/kotlin/com/studentstorefront`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront): backend application code
- [`src/main/resources/static`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/static): frontend shell, CSS, JS modules, layout configs
- [`src/test/kotlin/com/studentstorefront`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/test/kotlin/com/studentstorefront): backend tests
- [`bot/`](/home/zach/Desktop/productivity/Programming/SDE Sales App/bot): WhatsApp bot and evaluation tooling
- [`docs/REPOMAP.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/REPOMAP.md): repo map and API overview
- [`docs/architecture.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/architecture.md): high-level architecture notes
- [`specs/requirements.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/specs/requirements.md): requirements reference

## Core Features

- Seller registration and login with JWT auth
- Password reset and email verification flows
- Marketplace listing creation, update, search, and lifecycle management
- Listing images with Cloudinary upload support
- Favourites and review workflows
- WhatsApp QR login session support
- WhatsApp bot-assisted listing capture from chats/images
- Frontend layout system and builder-oriented static config

## Prerequisites

Install these locally before running the project:

- Java 21
- Node.js 20+ and `npm`
- Docker and Docker Compose
- PostgreSQL only if you do not want to use Docker

## Quick Start

1. Copy the backend env template:

```bash
cp .env.example .env
```

2. Copy the bot env template:

```bash
cp bot/.env.example bot/.env
```

3. Start PostgreSQL:

```bash
docker compose up -d db
```

4. Fill in the required values in `.env` and `bot/.env`.

5. Start the backend:

```bash
./gradlew bootRun
```

6. In a separate terminal, start the bot:

```bash
cd bot
npm install
node src/WhatsappBot.js
```

7. Open the app:

- Frontend/API base URL: `http://localhost:8080`
- Bot webhook default URL: `http://localhost:3001`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

## Installation

### 1. Install root dependencies

The root `package.json` is used for frontend validation tooling.

```bash
npm install
```

### 2. Install bot dependencies

```bash
cd bot
npm install
```

### 3. Start the database

The included [`docker-compose.yaml`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docker-compose.yaml) starts PostgreSQL with:

- Database: `studentstorefront`
- Username: `student`
- Password: `CHANGE_THIS`
- Port: `5432`

Run:

```bash
docker compose up -d db
```

If you change the DB credentials in Docker, keep the backend env vars aligned.

## Environment Configuration

This project uses environment variables. The backend reads variables through Spring property placeholders in [`application.properties`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/application.properties). The bot reads variables from `bot/.env` using `dotenv`.

Important: Spring Boot does not automatically load a root `.env` file by itself. You need to make those variables available to the process before starting the app, or use your shell/IDE run configuration to inject them.

Common approaches:

- Export variables in your shell before `./gradlew bootRun`
- Configure them in your IDE run configuration
- Use a tool such as `direnv` or a shell wrapper

### Backend `.env`

Start from [`.env.example`](/home/zach/Desktop/productivity/Programming/SDE Sales App/.env.example).

Recommended variables:

```env
DB_USERNAME=student
DB_PASSWORD=CHANGE_THIS
JWT_SECRET=replace-with-a-long-random-secret
MAIL_HOST=smtp.gmail.com
MAIL_USERNAME=your-email@example.com
MAIL_PASSWORD=your-app-password
APP_BASE_URL=http://localhost:8080
WHATSAPP_BOT_PHONE=15551234567
BOT_API_KEY=replace-with-a-shared-secret
WHATSAPP_BOT_API_URL=http://localhost:3001
CLOUDINARY_CLOUD_NAME=your-cloud-name
CLOUDINARY_API_KEY=your-cloudinary-key
CLOUDINARY_API_SECRET=your-cloudinary-secret
```

Backend variable notes:

- `DB_USERNAME`, `DB_PASSWORD`: PostgreSQL credentials
- `JWT_SECRET`: secret used to sign bearer tokens
- `MAIL_*`: SMTP settings for password reset and verification email flows
- `APP_BASE_URL`: public base URL used in generated links
- `BOT_API_KEY`: shared secret used between backend and bot
- `WHATSAPP_BOT_API_URL`: backend-to-bot callback base URL
- `CLOUDINARY_*`: required for media upload support

### Bot `bot/.env`

Start from [`bot/.env.example`](/home/zach/Desktop/productivity/Programming/SDE Sales App/bot/.env.example).

Key variables:

```env
SPRING_BASE_URL=http://localhost:8080
BOT_API_KEY=replace-with-your-bot-api-key
APP_BASE_URL=http://localhost:8080
BOT_WEBHOOK_PORT=3001
TARGET_GROUP_JID=your-whatsapp-group-jid
LLM_PRIMARY_PROVIDER=openai-compatible
LLM_FALLBACK_PROVIDERS=
GEMINI_API_KEY=replace-with-your-gemini-key
GEMINI_FALLBACK_MODELS=gemini-2.5-flash-lite,gemini-2.5-flash
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_API_KEY=replace-with-your-openai-key
OPENAI_MODEL=gpt-5.4-nano
LISTING_PARSE_MAX_TOKENS=2000
LANGFUSE_PUBLIC_KEY=
LANGFUSE_SECRET_KEY=
LANGFUSE_BASEURL=https://cloud.langfuse.com
```

Bot variable notes:

- `SPRING_BASE_URL`: backend API base URL
- `BOT_API_KEY`: must match the backend `BOT_API_KEY`
- `BOT_WEBHOOK_PORT`: Express webhook server port
- `TARGET_GROUP_JID`: WhatsApp group the bot should monitor
- `LLM_PRIMARY_PROVIDER`: currently `gemini` or `openai-compatible`
- `OPENAI_*` / `GEMINI_*`: credentials and model/provider routing
- `LANGFUSE_*`: optional observability integration

## Running The Project

### Start the database

```bash
docker compose up -d db
```

### Run the backend

From the repository root:

```bash
./gradlew bootRun
```

The Gradle config sets `SPRING_PROFILES_ACTIVE=local` for `bootRun`.

The app will start on:

- `http://localhost:8080`

### Run the bot

From [`bot/`](/home/zach/Desktop/productivity/Programming/SDE Sales App/bot):

```bash
npm install
node src/WhatsappBot.js
```

On first run, `whatsapp-web.js` will require WhatsApp authentication via QR code in the terminal.

## Development Workflow

### Backend

- Main application entrypoint: [`StudentStoreFrontApplication.kt`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/StudentStoreFrontApplication.kt)
- API controllers live in [`controller/`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/controller)
- Business logic lives in [`service/`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service)
- Persistence layer lives in [`repository/`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/repository)

### Frontend

- Main shell: [`index.html`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/static/index.html)
- App entrypoint: [`js/app.js`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/static/js/app.js)
- Layout configs: [`config/layouts/`](/home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/static/config/layouts)
- Theme reference: [`docs/THEMEMAP.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/THEMEMAP.md)

### Bot

- Main bot runtime: [`bot/src/WhatsappBot.js`](/home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/WhatsappBot.js)
- Spring API bridge: [`bot/src/services/springServices.js`](/home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/services/springServices.js)
- Listing flow orchestration: [`bot/src/services/ListingSubmissionService.js`](/home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/services/ListingSubmissionService.js)

## Testing And Validation

### Backend tests

Run:

```bash
./gradlew test
```

### Frontend validation

Run:

```bash
npm run check:frontend
```

This does two things:

- Syntax-checks every frontend JS file with `node --check`
- Validates registered frontend layout contracts

You can also run the contract check directly:

```bash
npm run test:frontend:contracts
```

### Bot evaluation utilities

From [`bot/`](/home/zach/Desktop/productivity/Programming/SDE Sales App/bot):

```bash
npm run eval:marketplace
npm run eval:slide
```

These are synthetic evaluation tools for marketplace extraction/reporting workflows, not general end-to-end tests.

## API Surface

The main backend areas are:

- Auth: registration, login, password reset, WhatsApp login session flow
- Posts: create, update, search, archive, mark sold
- Sellers: profile management and seller lookup
- Reviews: transaction review workflow
- Favourites: save/remove favourite listings

For a more detailed endpoint and entity reference, see [`docs/REPOMAP.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/REPOMAP.md).

If the app is running locally, Swagger UI is available at:

- `http://localhost:8080/swagger-ui.html`

## Common Local Issues

### Backend cannot connect to PostgreSQL

Check:

- Docker container is running: `docker compose ps`
- `DB_USERNAME` and `DB_PASSWORD` match the database container
- Port `5432` is free locally

### Email flows fail

Check:

- `MAIL_USERNAME` and `MAIL_PASSWORD`
- SMTP provider-specific requirements such as app passwords

### Image uploads fail

Check:

- `CLOUDINARY_CLOUD_NAME`
- `CLOUDINARY_API_KEY`
- `CLOUDINARY_API_SECRET`

### Bot cannot talk to the backend

Check:

- Backend is running on `http://localhost:8080`
- `SPRING_BASE_URL` is correct in `bot/.env`
- `BOT_API_KEY` matches on both sides
- `WHATSAPP_BOT_API_URL` points to the bot server from the backend side

### WhatsApp bot does not authenticate

Check:

- The terminal QR flow completed successfully
- Session files were preserved as expected
- The WhatsApp account is permitted to use WhatsApp Web

## Additional Documentation

- [`docs/REPOMAP.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/REPOMAP.md)
- [`docs/architecture.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/architecture.md)
- [`docs/PROPOSAL.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/PROPOSAL.md)
- [`bot/DOCUMENTATION.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/bot/DOCUMENTATION.md)
- [`bot/LLM_TESTING_REPORT.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/bot/LLM_TESTING_REPORT.md)

## Current Defaults And Assumptions

- Backend port: `8080`
- Bot webhook port: `3001`
- PostgreSQL port: `5432`
- Default DB name: `studentstorefront`
- Java toolchain: `21`

## License

No project license file is currently present in the repository. Add one if this project is intended for redistribution or external collaboration.
