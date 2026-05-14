# Student Storefront Domain And Critical Scenarios

## Domain Summary

Student Storefront is a campus-only marketplace for Constructor University students. It replaces unstructured WhatsApp selling with a verified web platform where students can post listings, browse current offers, mark completed sales, and leave transaction reviews.

To reconstruct the database schema from this description, you need to know the system has four core business concepts and four supporting auth/session concepts:

1. `Seller`
   Every account belongs to one person in the university community. Sellers have a university email, a phone number used for WhatsApp contact, a password hash, an enabled/disabled flag, and a role (`SELLER` or `ADMIN`).

2. `Post`
   A post is one marketplace listing created by one seller. Each listing has a title, price, description, category, lifecycle status, creation timestamp, expiration timestamp, sold flag, and optional buyer reference once the sale is completed.

3. `PostMedia`
   A post can have zero or more images. Each image belongs to exactly one post, has a hosted media URL, an optional source-image hash for deduplication, a display order, and a cover-image flag.

4. `Review`
   Reviews are tied to completed sales. Each review belongs to one sold post, one reviewer, and one reviewee. A review stores the direction of the review (`BUYER_TO_SELLER` or `SELLER_TO_BUYER`), a 1-5 rating, an optional comment, and a creation timestamp. Each transaction can produce at most two reviews: one in each direction.

5. `Favourite`
   A favourite connects one seller account to one post they want to save. The same seller cannot favourite the same post twice.

6. `PasswordResetToken`
   Password resets are handled with time-limited tokens linked to sellers. A token can be used at most once.

7. `EmailVerificationToken`
   New registrations are verified by a 6-digit code linked to a seller. The code expires and can be marked as used.

8. `WhatsAppLoginSession`
   QR-based WhatsApp login is modeled as a short-lived session with UUID-based identifiers and a status lifecycle (`PENDING`, `COMPLETED`, `CLAIMED`, `PHONE_NOT_LINKED`, `EXPIRED`). A session may later be linked to a seller once the phone number is matched.

## Rules That Shape The Schema

- Each seller email is unique.
- Each seller phone number is unique.
- Every post has exactly one seller.
- A post may optionally have one buyer after it is sold.
- A post can have many media rows, ordered by `display_order`.
- A favourite is unique per `(seller_id, post_id)`.
- A review is unique per `(post_id, direction)`.
- `posts.status` is separate from `posts.is_sold`.
  This lets the app distinguish listing visibility (`ACTIVE` vs `ARCHIVED`) from transaction completion (`is_sold`).
- Unverified or abandoned signups can exist as disabled seller rows.
- Password-reset tokens, verification codes, and WhatsApp login sessions are stored as first-class tables because they have expiry and one-time-use behavior.

## Critical Scenarios

### 1. Seller Registration And Verification

1. A student submits name, `@constructor.university` email, phone number, and password.
2. The system creates or refreshes a disabled seller row.
3. A verification-code row is inserted into `email_verification_tokens`.
4. Once the student confirms the code, the seller becomes enabled and can log in.

Database impact:

- Insert into `sellers`
- Insert into `email_verification_tokens`
- Update `sellers.is_enabled`
- Update `email_verification_tokens.used`

### 2. Buyer Browsing And Search

1. A student visits the marketplace.
2. The app reads active, unsold posts with seller data and ordered media.
3. The student filters by category or searches by title/description.
4. Optionally, the student favourites a listing for later.

Database impact:

- Read from `posts`, `sellers`, `post_media`
- Optional insert or delete in `favourites`

### 3. Seller Creates Or Edits A Listing

1. An authenticated seller submits title, price, description, category, and images.
2. One `posts` row is inserted.
3. One or more `post_media` rows are inserted with stable ordering.
4. When editing, the same `post_id` is retained and media rows may be reordered or replaced.

Database impact:

- Insert or update `posts`
- Insert, delete, and reorder `post_media`

### 4. Seller Marks A Listing Sold

1. The listing owner selects the buyer from registered accounts.
2. The post is updated with `is_sold = true`, `buyer_id`, and `sold_at`.
3. This creates the context needed for both review directions.

Database impact:

- Update `posts.is_sold`
- Update `posts.buyer_id`
- Update `posts.sold_at`

### 5. Both Sides Leave Reviews

1. The buyer reviews the seller.
2. The seller reviews the buyer.
3. Each review is stored once per direction.
4. Profile pages aggregate rating averages and review counts separately for seller reputation and buyer reputation.

Database impact:

- Insert into `reviews`
- Read aggregated data from `reviews`

### 6. WhatsApp QR Login

1. The web app creates a short-lived QR login session.
2. The bot confirms the phone number and updates the session.
3. If the phone number belongs to an enabled seller, the session becomes claimable.
4. The frontend exchanges the claim token for a JWT and the session becomes `CLAIMED`.

Database impact:

- Insert into `whatsapp_login_sessions`
- Update `status`, `seller_id`, `phone_number`, `completed_at`, and `claimed_at`

## Why These Tables Are Indexed

The most important runtime queries in the current app are:

- public marketplace reads of active unsold posts
- category-filtered marketplace reads
- scheduler scans for expiring posts
- per-seller profile listing lookups
- ordered image lookup per post
- favourites existence checks and lookups
- review summary lookups by reviewee and direction
- exact-token and exact-phone/email lookups in auth flows

The SQL schema therefore adds indexes on:

- active public listing paths in `posts`
- expiration-window scans in `posts`
- relationship keys like `seller_id`, `buyer_id`, and `post_id`
- review summary access paths
- token/session tables used by one-time auth flows

For larger datasets, PostgreSQL trigram or full-text indexes would be the next optimization step for `LIKE`-based marketplace search, but the checked-in schema stays focused on the query patterns already implemented in the app.
