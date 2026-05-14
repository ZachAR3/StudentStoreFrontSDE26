# Database Audit Report

Date: 2026-05-14

Scope:
- `docs/REPOMAP.md`
- `docs/database/*`
- Kotlin backend entity, repository, service, controller, config, scheduler, and test files that affect persistence behavior

## Executive Summary

The project has a reasonable relational core for a campus marketplace, but the runtime database behavior is less strict than the checked-in documentation suggests.

The highest-risk problems are:

1. Public endpoints expose seller and buyer PII directly from database rows.
2. Verification and reset secrets are stored in plaintext, while `spring.jpa.show-sql=true` can leak them again into logs.
3. The documented SQL schema is not the actual source of truth at runtime because Hibernate is allowed to mutate the schema with `ddl-auto=update`, while the entities do not encode most of the documented constraints.
4. Several important row-state invariants are enforced only partially in service code, so invalid combinations such as sold posts without a buyer or renewed sold posts are possible.

There is no obvious classic SQL injection in the JPA query layer. Most repository queries use parameter binding, and the `LIKE` search paths escape `%`, `_`, and the escape character itself. The main attack surface is data exposure, weak persistence invariants, and operational misconfiguration rather than direct SQL string concatenation.

## How The Database Actually Works

The intended schema is documented in `docs/database/schema.sql`, but the application boot path is driven by:

- `src/main/resources/application.properties:17` with `spring.jpa.hibernate.ddl-auto=update`
- entity annotations in `src/main/kotlin/com/studentstorefront/entity/*`
- boot-time repair logic in `src/main/kotlin/com/studentstorefront/config/DataLoader.kt`

That means the practical source of truth is not the checked-in SQL file. The runtime database is shaped by Hibernate first, and the SQL file is mostly documentation unless the team explicitly applies it outside Spring.

Main table lifecycle:

- `sellers`
  - created through `SellerService.createSellerWithToken` and `createSeller`
  - reused or merged for abandoned unverified registrations
  - manually cleaned up through `clearSellerDependencies`
- `posts`
  - created through `PostService.createPost`, `createPostWithImages`, and `createPostAsBot`
  - archived by `PostExpirationScheduler.archiveExpiredPosts`
  - renewed by `PostService.renewPost`
- `post_media`
  - written separately from `posts`
  - read by repeated repository lookups instead of the mapped `Post.postMedia` association
- `reviews`, `favourites`, token tables, and `whatsapp_login_sessions`
  - maintained mostly through service-layer rules rather than database-native cascades or checks

## Findings

### Critical 1: Public endpoints leak seller and buyer PII straight from database rows

What is wrong:
- `SellerResponseDTO` includes `email` and `phoneNumber` for every seller row (`src/main/kotlin/com/studentstorefront/dto/response/SellerResponseDTO.kt:3-7`).
- `PostResponseDTO` embeds full `seller` and optional `buyer` objects (`src/main/kotlin/com/studentstorefront/dto/response/PostResponseDTO.kt:8-22`).
- `PostController` exposes `GET /api/posts`, `GET /api/posts/{postId}`, `GET /api/posts/seller/{sellerId}`, `GET /api/posts/search`, `GET /api/posts/category/{category}`, and `GET /api/posts/available` publicly (`src/main/kotlin/com/studentstorefront/controller/PostController.kt:76-137`).
- `SellerController` exposes `GET /api/sellers/{sellerId}` and `GET /api/sellers/search` without method-level authorization (`src/main/kotlin/com/studentstorefront/controller/SellerController.kt:53-65`).
- `SecurityConfig` explicitly permits `GET /api/sellers/*` and all `GET /api/posts/**` (`src/main/kotlin/com/studentstorefront/config/SecurityConfig.kt:32-34`).

Why it is bad:
- Any anonymous caller can enumerate seller email addresses and phone numbers.
- Sold listings can expose buyer identity, email, and phone number publicly.
- `docs/REPOMAP.md` says seller search and seller-by-id are `SELLER/ADMIN` endpoints, but implementation exposes them publicly (`docs/REPOMAP.md:133-140`).

Why it is specifically a database problem:
- The API returns raw contact fields from core relational rows instead of using a reduced public projection.
- This is not just a routing mistake; it is unsafe data modeling at the DTO boundary.

Recommended direction:
- Split seller projections into public and private DTOs.
- Remove `email` and `phoneNumber` from all public post payloads.
- Re-protect seller search and seller-by-id endpoints.
- Consider whether `buyer` should ever be returned outside the two transaction participants.

### Critical 2: Password reset tokens and email verification codes are stored in plaintext, and SQL logging is enabled

What is wrong:
- `PasswordResetToken.token` is stored as a raw UUID string (`src/main/kotlin/com/studentstorefront/entity/PasswordResetToken.kt:13-15`).
- `EmailVerificationToken.code` is stored as a raw 6-digit code (`src/main/kotlin/com/studentstorefront/entity/EmailVerificationToken.kt:13-15`).
- Lookups compare these values directly from user input (`src/main/kotlin/com/studentstorefront/service/PasswordResetService.kt:35-37`, `src/main/kotlin/com/studentstorefront/service/EmailVerificationService.kt:28-30`).
- SQL logging is enabled globally with `spring.jpa.show-sql=true` (`src/main/resources/application.properties:17-18`).

Why it is bad:
- A database leak exposes valid reset links and verification codes immediately.
- Verbose SQL logging can duplicate those secrets into log storage.
- The reset link includes the raw token in the URL (`src/main/kotlin/com/studentstorefront/service/PasswordResetService.kt:31`), so plaintext persistence increases blast radius further.

Recommended direction:
- Store only hashed reset tokens and hashed verification codes.
- Compare with constant-time hash checks.
- Disable SQL logging outside local development.
- Add explicit token/session retention cleanup so expired secrets do not remain in the database indefinitely.

### Critical 3: Verification and login-related tables are brute-forceable or easy to abuse

What is wrong:
- Rate limiting only covers `/api/auth/login`, `/api/auth/forgot-password`, and `/api/auth/resend-verification` (`src/main/kotlin/com/studentstorefront/config/RateLimitFilter.kt:21-26`).
- `POST /api/auth/verify-email` is not rate-limited even though it validates a 6-digit code (`src/main/kotlin/com/studentstorefront/controller/AuthController.kt:62-69`, `src/main/kotlin/com/studentstorefront/service/EmailVerificationService.kt:28-38`).
- `POST /api/auth/whatsapp/session` is public and unthrottled, so an attacker can create large numbers of session rows (`docs/REPOMAP.md:104-107`, `src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:33-41`).
- IP identity is based on a blindly trusted `X-Forwarded-For` header (`src/main/kotlin/com/studentstorefront/config/RateLimitFilter.kt:66-69`) and that same value is stored in `whatsapp_login_sessions.creator_ip` (`src/main/kotlin/com/studentstorefront/entity/WhatsAppLoginSession.kt:31-38`).

Why it is bad:
- Six-digit codes are small enough that missing throttling matters.
- Session-creation spam can bloat the session table.
- Header spoofing lets attackers sidestep per-IP controls and poison stored audit data.

Recommended direction:
- Rate-limit `verify-email`, WhatsApp session creation, claim, and confirm flows.
- Only trust `X-Forwarded-For` behind a known proxy layer.
- Add cleanup for expired and claimed WhatsApp sessions, not just status updates.

### High 4: The documented SQL schema is not the runtime schema

What is wrong:
- The repository documents strong constraints in `docs/database/schema.sql`, including `NOT NULL`, length limits, numeric checks, enum checks, and uniqueness (`docs/database/schema.sql:3-141`).
- The entities do not encode most of those rules. Example:
  - `Seller.email` is only `@Column(unique = true)` with no `nullable = false`, no length, and no domain check (`src/main/kotlin/com/studentstorefront/entity/Seller.kt:12-21`).
  - `Post` lacks most column-level constraints from the SQL file (`src/main/kotlin/com/studentstorefront/entity/Post.kt:11-32`).
  - `Post.seller` is nullable in the entity even though the SQL schema says `seller_id` is `NOT NULL` (`docs/database/schema.sql:37`, `src/main/kotlin/com/studentstorefront/entity/Post.kt:25-27`).
- Hibernate is configured to update the schema automatically (`src/main/resources/application.properties:17`).

Why it is bad:
- On a fresh database, actual constraints will follow Hibernate metadata, not `docs/database/schema.sql`.
- DTO validation only protects API writes; it does not protect direct repository writes, migration scripts, tests, admin tooling, or future integrations.
- The project currently has two schemas: the documented one and the effective one.

Recommended direction:
- Make migrations the single source of truth.
- Replace `ddl-auto=update` with validated migrations.
- Move key business invariants into database constraints where possible.

### High 5: Post state invariants are weak and can produce inconsistent rows

What is wrong:
- `PostService.createPostEntity` accepts client-controlled `isSold` during creation (`src/main/kotlin/com/studentstorefront/service/PostService.kt:212-223`).
- No buyer or `soldAt` is required when `isSold = true`.
- `markAsSold` accepts any seller row as buyer, not just enabled/registered accounts (`src/main/kotlin/com/studentstorefront/service/PostService.kt:172-187`).
- `renewPost` reactivates a post without checking that it is archived/expired/unsold (`src/main/kotlin/com/studentstorefront/service/PostService.kt:190-198`), even though the repo map describes renewal as only for archived or expired listings (`docs/REPOMAP.md:118`).
- The SQL schema only prevents buyer = seller (`docs/database/schema.sql:37-39`); it does not enforce sold-state consistency.

Examples of invalid states the current model permits:
- `is_sold = true` with `buyer_id IS NULL`
- `is_sold = true` with `sold_at IS NULL`
- `status = ACTIVE` plus `is_sold = true` after a bot renewal
- `buyer_id` pointing to a disabled or unverified seller

Recommended direction:
- Remove `isSold` from creation DTOs.
- Add service checks and database checks for sold-state invariants.
- Restrict renewals to non-sold archived posts.
- Require `buyer.isEnabled == true` when marking a sale complete.

### High 6: Media deduplication is nondeterministic, under-indexed, and internally inconsistent

What is wrong:
- `imageHash` is used to deduplicate media URLs (`src/main/kotlin/com/studentstorefront/service/PostService.kt:240-263`).
- The schema does not define a unique constraint or even an index on `post_media.image_hash` (`docs/database/schema.sql:42-50`, `121-141`).
- `resolveMediaUrlsByHash` loads every row with those hashes and picks an arbitrary first row per hash using `distinctBy` (`src/main/kotlin/com/studentstorefront/service/PostService.kt:244-247`).
- `isCover` duplicates ordering semantics and is not protected by a `one cover per post` constraint (`docs/database/schema.sql:42-50`).

Why it is bad:
- Two rows can legally share the same hash but point to different URLs.
- Which URL gets reused depends on query result order, not on a deterministic rule.
- `isCover` and `displayOrder` can drift apart because both try to encode presentation order.

Recommended direction:
- Decide whether `imageHash` is metadata or a true dedup key.
- If it is a dedup key, add an index and probably a unique constraint or a canonical media table.
- Drop `isCover` or derive it from `displayOrder = 0`.
- If `isCover` remains, enforce exactly one cover per post.

### High 7: `sellers` is really the accounts table, and the current role model is underdesigned

What is wrong:
- The table is named `sellers`, but the code also uses those same rows as buyers:
  - `posts.buyer_id` references `sellers` (`docs/database/schema.sql:38`)
  - `Post.buyer` is a `Seller` entity (`src/main/kotlin/com/studentstorefront/entity/Post.kt:28-30`)
  - review direction is derived from whether the current account is the listing owner or the buyer account (`src/main/kotlin/com/studentstorefront/service/ReviewService.kt:139-145`)
- The `Role` enum only has two values: `SELLER` and `ADMIN` (`src/main/kotlin/com/studentstorefront/enums/Role.kt:3-4`).
- In practice, the only meaningful distinction is “admin or normal account”:
  - security authorities are built from `seller.role` (`src/main/kotlin/com/studentstorefront/service/CustomUserDetailsService.kt:23-31`)
  - write endpoints are mostly `hasAnyRole('SELLER', 'ADMIN')`, which means both roles can do the same marketplace work (`src/main/kotlin/com/studentstorefront/controller/PostController.kt:29-37`, `139-172`; `src/main/kotlin/com/studentstorefront/controller/ReviewController.kt:22-40`; `src/main/kotlin/com/studentstorefront/controller/FavouriteController.kt:17-30`)
  - the main special case is admin override behavior in services (`src/main/kotlin/com/studentstorefront/service/PostService.kt:43`, `57`, `390-394`; `src/main/kotlin/com/studentstorefront/service/SellerService.kt:111-113`)

Why it is bad:
- The table name is misleading. It stores authenticated people/accounts, not just sellers.
- The domain language is inconsistent: the same person can be a seller on one post and a buyer on another, but the schema still calls every account a seller.
- The `role` column looks more powerful than it really is. Right now it is mostly an admin flag disguised as a general authorization system.

Evaluation of the design question:
- Adding a separate `buyers` table would be the wrong direction for the current product.
- Buyers are not a different entity type in this app; they are the same authenticated accounts acting in a different transaction role.
- Splitting `sellers` and `buyers` would duplicate identity data and complicate reviews, favourites, authentication, and sale history for little benefit.

Better options:
- Best fit for the current design:
  - rename `sellers` to `users` or `accounts`
  - rename `Seller` to `User` or `Account`
  - keep a minimal admin capability field
- If the product really needs role-based feature separation later:
  - keep a `users` table for identity
  - model permissions/capabilities separately from transaction roles
  - do not confuse “buyer/seller in a transaction” with “authorization role in the system”

Practical conclusion:
- Do not add a `buyers` table.
- Either:
  - rename the current table/entity to `users` and keep only an admin flag or a renamed role field, or
  - keep roles only if the project is genuinely going to support more than “normal user” and “admin”.

### High 8: `buyer_id` on `posts` is understandable, but it mixes listing state with transaction state

What is wrong:
- `buyer_id` is stored directly on the `posts` row (`docs/database/schema.sql:38`, `src/main/kotlin/com/studentstorefront/entity/Post.kt:28-30`).
- `markAsSold` writes buyer identity and sale completion directly back into the listing row (`src/main/kotlin/com/studentstorefront/service/PostService.kt:172-187`).
- Reviews and pending-review queries depend on that embedded relationship (`src/main/kotlin/com/studentstorefront/service/ReviewService.kt:35-56`, `79-104`, `130-145`).

Why it exists:
- It gives the app a simple way to say “this listing was sold to this registered account”.
- It supports the current review workflow without needing a separate transaction table.
- For a very small MVP where each post can only ever have one completed sale, it is a workable shortcut.

Why it is still suboptimal:
- A marketplace listing and a completed sale are different concepts.
- `posts` should represent the listing lifecycle.
- `buyer_id` and `sold_at` represent a transaction outcome.
- Storing both in one row creates the weak invariants already noted earlier:
  - sold posts can exist without a buyer
  - sold posts can exist without `sold_at`
  - renewal can touch rows that already contain sale data
- It also limits future evolution:
  - no clean place for transaction-specific fields like sale price snapshot, payment method, pickup status, cancellation reason, dispute status, or buyer confirmation
  - no history if the workflow ever needs failed/aborted sale attempts before final completion

Evaluation:
- `buyer_id` is not pointless. In the current codebase it is central to how sold-post reviews work.
- But it is not the cleanest long-term design.

Better direction:
- Introduce a dedicated `sales` or `transactions` table, for example:
  - `id`
  - `post_id`
  - `seller_id`
  - `buyer_id`
  - `sold_at`
  - optional `sale_price`
  - optional transaction-status fields
- Then:
  - `posts` remains the listing
  - the transaction row becomes the review anchor
  - review uniqueness can attach to the transaction instead of overloading the post row

Practical conclusion:
- Keeping `buyer_id` in `posts` is acceptable only as an MVP shortcut.
- If the project is being cleaned up for correctness and extensibility, `buyer_id` should move out of `posts` into a dedicated transaction table.

### Medium 9: ORM mapping contains dead or misleading structure

What is wrong:
- `Post.postMedia` is mapped with `cascade = [CascadeType.ALL]` (`src/main/kotlin/com/studentstorefront/entity/Post.kt:31-32`).
- The application almost never uses that association; it always reloads media through `PostMediaRepository.findByPost_postId` (`src/main/kotlin/com/studentstorefront/service/PostService.kt:371-373`).
- Because media is managed manually in services, the ORM association gives a false impression that post/media lifecycle is modeled centrally when it is not.

Why it is bad:
- Future maintainers may assume cascade behavior is the main deletion/update path.
- The model is harder to reason about because persistence ownership is split between entity mapping and service code.

Recommended direction:
- Either use the mapped association as the true source of lifecycle management, or remove/trim it and make the repository-driven approach explicit.

### Medium 10: The code relies on manual dependency cleanup instead of database-native cascades

What is wrong:
- Seller deletion manually removes tokens, favourites, reviews, sessions, buyer references, and owned posts in a specific order (`src/main/kotlin/com/studentstorefront/service/SellerService.kt:229-241`).
- Post deletion manually removes favourites and reviews before deleting the post (`src/main/kotlin/com/studentstorefront/service/PostService.kt:164-170`).
- The SQL schema uses plain foreign keys without `ON DELETE CASCADE` (`docs/database/schema.sql:37-105`).

Why it is bad:
- The application must get deletion order exactly right forever.
- Any direct SQL delete, batch job, or future service path can break referential integrity if it bypasses this sequence.
- The cleanup logic is repeated at the service layer instead of being pushed into the database where appropriate.

Recommended direction:
- Add database-native cascades where business rules allow them.
- Keep explicit cleanup only where the action has business meaning, not just referential maintenance.

### Medium 11: Listing reads have avoidable N+1 query behavior

What is wrong:
- Every `PostResponseDTO` mapping performs:
  - one query for ordered media (`src/main/kotlin/com/studentstorefront/service/PostService.kt:347`, `371-373`)
  - another existence lookup for favourites when authenticated (`src/main/kotlin/com/studentstorefront/service/PostService.kt:357-358`)
- List endpoints map whole pages through this method (`src/main/kotlin/com/studentstorefront/service/PostService.kt:95-137`).

Why it is bad:
- Page reads scale linearly in extra queries.
- This is especially expensive for the public marketplace endpoints, which are likely the hottest read paths.

Recommended direction:
- Fetch media in batch or via projection.
- Compute favourite flags in bulk.
- Consider a dedicated read model for public listing cards.

### Medium 12: Some tables carry dead or weakly justified fields

Likely dead or weak-value fields:

- `favourites.created_at`
  - present in entity and schema (`src/main/kotlin/com/studentstorefront/entity/Favourite.kt:22-23`, `docs/database/schema.sql:52-58`)
  - not queried, surfaced, or used by any business logic found in the repo
- `whatsapp_login_sessions.creator_ip`
  - stored (`src/main/kotlin/com/studentstorefront/entity/WhatsAppLoginSession.kt:31-38`)
  - not used for auditing, security decisions beyond the external rate limiter, or admin tooling
  - currently accepts spoofed header input
- `post_media.is_cover`
  - not dead, but redundant enough that it behaves like a denormalized field without integrity protection

Recommendation:
- Remove fields that do not drive current product behavior, or add the missing behaviors that justify storing them.

### Medium 13: Token and session retention is weak and will accumulate stale rows

What is wrong:
- Email verification rows are marked `used` but not routinely pruned (`src/main/kotlin/com/studentstorefront/service/EmailVerificationService.kt:28-38`).
- Password reset rows are only pruned opportunistically during successful reset completion (`src/main/kotlin/com/studentstorefront/service/PasswordResetService.kt:52-55`).
- WhatsApp sessions are marked expired, but never cleaned up (`src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:127-130`).
- Claim-token expiry returns `null` without updating row state (`src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:98-100`).

Why it is bad:
- Token/session tables will grow continuously.
- Retained auth artifacts increase operational and security risk.

Recommended direction:
- Add scheduled deletion or archival windows for expired and used auth artifacts.

### Low 14: Startup data repair and demo seeding logic are in the production boot path

What is wrong:
- `DataLoader` mutates persisted data on every boot with `UPDATE posts SET status = 'ACTIVE' WHERE status IS NULL` (`src/main/kotlin/com/studentstorefront/config/DataLoader.kt:20-24`).
- It also creates a default demo seller when the table is empty (`src/main/kotlin/com/studentstorefront/config/DataLoader.kt:26-35`).

Why it is bad:
- Startup code is acting like an informal migration system.
- Demo data logic in the main application path increases drift between environments.
- The default account uses a trivial password literal before hashing.

Recommended direction:
- Move repairs into migrations.
- Move demo/test seeding behind profiles.

## Consistency Problems Between Docs And Implementation

1. `docs/REPOMAP.md` says seller search and seller-by-id are `SELLER/ADMIN`, but implementation exposes them publicly (`docs/REPOMAP.md:133-140`, `src/main/kotlin/com/studentstorefront/controller/SellerController.kt:53-65`, `src/main/kotlin/com/studentstorefront/config/SecurityConfig.kt:33`).
2. `docs/REPOMAP.md` describes renewal as an archived/expired-post flow, but `PostService.renewPost` does not enforce that state (`docs/REPOMAP.md:118`, `src/main/kotlin/com/studentstorefront/service/PostService.kt:190-198`).
3. `docs/REPOMAP.md` documents `PasswordResetToken.expiryDate`, while code and SQL use `expiresAt` / `expires_at` (`docs/REPOMAP.md:224-229`, `src/main/kotlin/com/studentstorefront/entity/PasswordResetToken.kt:20-27`, `docs/database/schema.sql:72-79`).
4. The SQL schema contains stronger constraints than the entity model, so the documented DB and the Hibernate-built DB are not equivalent.

## Injection Review

### What looks safe

- Repository search queries use parameter binding rather than string concatenation (`src/main/kotlin/com/studentstorefront/repository/PostRepository.kt:35-52`, `src/main/kotlin/com/studentstorefront/repository/SellerRepository.kt:17-32`).
- `LIKE` inputs are escaped in both seller and post search flows (`src/main/kotlin/com/studentstorefront/service/PostService.kt:375-376`, `src/main/kotlin/com/studentstorefront/service/SellerService.kt:174-175`).

### Potential injection-adjacent issues

- `X-Forwarded-For` is trusted without proxy validation (`src/main/kotlin/com/studentstorefront/config/RateLimitFilter.kt:66-69`).
- Public DTOs expose raw stored data broadly enough that the bigger risk is data exfiltration, not query injection.

Conclusion:
- No direct SQL injection sink was found in the reviewed database paths.
- The important vulnerabilities are authorization, secret storage, schema drift, and row-state integrity.

## Testing And Verification Notes

- I ran `./gradlew test`.
- Result: the suite failed because several integration tests require Docker/Testcontainers in this environment, not because the code failed to compile.
- Failing test classes included `ApplicationTests`, `PostExpirationControllerTest`, `PostSearchControllerTest`, `PostUpdateControllerTest`, `ReviewWorkflowControllerTest`, and `WhatsAppQrLoginControllerTest`, all with `DockerClientProviderStrategy` initialization errors.

## Priority Fix Order

1. Stop public PII exposure from seller and post read models.
2. Disable SQL logging outside development and hash all reset/verification secrets at rest.
3. Add rate limits to verification and WhatsApp session flows; stop trusting arbitrary `X-Forwarded-For`.
4. Replace `ddl-auto=update` with real migrations and move documented constraints into the runtime schema.
5. Enforce sold-post invariants in both service logic and database checks.
6. Normalize media modeling: decide between canonical media dedup or plain per-post media rows, and remove redundant cover state.
