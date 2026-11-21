# Student Storefront Domain And Critical Scenarios

Student Storefront is a verified campus marketplace. The Spring application owns
the PostgreSQL database; the browser and WhatsApp bot use the HTTP API and never
write database rows directly.

## Domain model

1. `User` — a normalized email/phone account with a BCrypt password hash, role
   (`USER` or `ADMIN`), enabled/registration state, optimistic-lock version, JWT
   token version, and phone verification timestamp. Public user responses contain
   only the display name.
2. `Post` — one listing owned by a user. Its price is stored with two decimal
   places. `status` (`ACTIVE`, `ARCHIVED`, `SOLD`) and `is_sold` are kept in sync
   by a database check; `version` protects concurrent edits and
   `idempotency_key` makes bot retries safe per seller.
3. `PostMedia` — ordered HTTPS media references with optional SHA-256 hashes and
   provider asset identities used for rollback and reference-aware cleanup.
   Media are optional; each post has at most five rows and at most one cover row.
   An empty media collection has no cover.
4. `Sale` — the transaction record for a sold post. It stores the seller, buyer,
   buyer-confirmation time, and title/price snapshots so later listing edits
   cannot rewrite transaction history.
5. `Review` — a review of a completed sale. Each sale allows one review in each
   direction (`BUYER_TO_SELLER` and `SELLER_TO_BUYER`), with a rating from 1 to 5.
6. `Favourite` — a unique `(user_id, post_id)` relationship.
7. `PasswordResetToken` and `EmailVerificationToken` — one-time credentials
   stored as hashes, with expiry, usage state, and failed verification attempts.
8. `WhatsAppLoginSession` — a short-lived QR challenge with separate polling,
   login, and claim UUIDs. Claiming is row-locked and changes the state once.
9. `api_rate_limit_windows` — a shared per-IP/per-route minute window used when
   multiple backend instances share PostgreSQL.
10. `notification_outbox` — transactionally recorded email/bot intents with
    deduplication, leases, retry backoff, and short payload retention.

Migration-only quarantine tables retain legacy sold posts or reviews that cannot
be mapped safely. They are deliberately not exposed through the application API.

## Invariants and visibility rules

- Emails are trimmed/lowercased and phone numbers use a canonical `+digits`
  form before uniqueness checks.
- An anonymous reader sees only active, unsold listings and never seller contact
  details or buyer identity. Sellers, buyers, and admins can see the private
  details needed for their own transaction/profile flows.
- A sale must have distinct enabled users and one post. Sold posts cannot be
  edited, deleted, or renewed.
- The seller selects an enabled, registered buyer when completing the listing,
  which creates a pending sale. The selected buyer must confirm it through the
  authenticated API before either account can submit a review. Once confirmed,
  the sale and its two directional reputation slots are immutable.
- Account deletion is physical only when the user has no sale history. A user
  with sale history is anonymized in place so sales and reviews remain valid.
  A durable bot cleanup notification removes phone-keyed drafts and consent
  state after the database transaction commits.
- Expired credentials and QR sessions are cleaned by a scheduled job; stale
  completed QR challenges cannot be claimed.
- Flyway migrations are the upgrade path. `schema.sql` is a clean-install
  reference and must remain aligned with the runtime entities and migrations.

## Critical scenarios

### 1. Registration and email verification

1. Normalize the email and phone and create or refresh a pending user.
2. Hash a six-digit code and store it with a 15-minute deadline.
3. Lock the code row during verification, count failed attempts, and consume it
   once. A successful initial verification enables the user and revokes old JWTs.
4. Email changes use `pending_email`; the current email is not replaced until a
   fresh code is verified.

Database impact: `users` insert/update, `email_verification_tokens` insert,
lock, consume, and scheduled cleanup, plus a durable verification-email and
bot-registration outbox row.

### 2. Browse and search

1. Read active, unsold posts using bounded pages and a stable `(created_at,
   post_id)` tie-breaker.
2. Load ordered media and favourites in batches for the page.
3. Return public user names only; contact fields are returned only to the seller,
   buyer, or admin.

Database impact: `posts`, `users`, `post_media`, and optional `favourites` reads.

### 3. Create or edit a listing

1. Validate title, description, price precision, category, URL scheme/host, image
   count, and image hashes before persistence.
2. For uploads, use generated temporary filenames, compensate provider assets on
   rollback, and reject partial upload results. For bot submissions, reuse
   matching SHA-256 media and send a stable idempotency key.
3. Preserve retained `post_media.id` rows when reordering; replace only removed
   or new media.

Database impact: one `posts` row plus ordered `post_media` rows in one transaction.

### 4. Complete a sale

1. Lock the post row, verify ownership, active state, enabled distinct buyer,
   and absence of an existing sale.
2. Update the post to `SOLD` and insert one pending `sales` row with snapshots.
   A unique post constraint and database checks protect retries and raw SQL paths.
3. The selected buyer confirms the sale through the authenticated API.
4. Record the buyer notification in the same transaction and deliver it after
   commit with bounded retries; an unavailable bot cannot roll back the sale.

### 5. Reviews and reputation

1. The buyer or seller can review only a participant in a completed sale.
2. The `(sale_id, direction)` unique key prevents duplicates.
3. Profile summaries are SQL aggregates; recent reviews and pending review
   contexts are bounded.

### 6. WhatsApp QR login

1. Create a five-minute pending session and display its login UUID in a QR code.
2. The bot authenticates with its scoped key and confirms the sender's canonical
   phone. A successful confirmation marks the phone verified and creates one
   short-lived claim token.
3. A pessimistic row lock allows exactly one claim to issue a JWT; later claims
   return no token. Scheduled cleanup expires and deletes old sessions.

### 7. Listing renewal

1. The scheduled reminder is sent while a listing is still active but within its
   final hour, or after the scheduler archives it.
2. A renewal request must carry the seller's sender-bound phone number. The
   backend locks the post, rejects sold/unrelated listings, and allows only an
   archived or currently expiring listing to move back to active for two days.

## Index strategy

The runtime schema indexes active marketplace reads, stable expiration scans,
seller/buyer relationship lookups, ordered media, image-hash reuse, review
summaries, sale participants, one-time credentials, QR sessions, and favourites.
Search still uses escaped `LIKE` predicates; a trigram index can be added after a
measured workload justifies the extension.
