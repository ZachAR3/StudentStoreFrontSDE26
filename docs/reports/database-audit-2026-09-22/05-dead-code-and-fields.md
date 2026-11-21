# Dead code, dead fields and duplication (`D-*`)

[Report index](README.md)

"Dormant" means no production read/write path exists in this working tree
(grep-verified across `src/main` and `bot/`). Do not drop anything without checking
deployed data and external consumers first.

---

## D-1 — `whatsapp_login_sessions.creator_ip` is write-only (Low)

**Where:** `entity/WhatsAppLoginSession.kt` (`val creatorIp: String`),
`WhatsAppQrLoginService.createSession(ip)`, column `creator_ip VARCHAR(255) NOT NULL`.

**What's wrong:** the column is populated on every session creation and **never read
by any runtime decision** — poll, confirm, and claim all ignore it (the only
"readers" are tests constructing entities). It was clearly meant to bind the
QR session to the creating browser (V-4), so as-is it is PII (IP addresses) stored
for nothing: GDPR-style retention noise plus a false sense of binding.

**Fix:** either enforce it on `pollSession`/`claimJwt` (V-4's fix) or drop the
column and the controller's `request.remoteAddr` plumbing.

## D-2 — `SaleRepository.findBySellerUserId` / `findByBuyerUserId` have no callers (Low)

**Where:** `repository/SaleRepository.kt:17-18`.

**What's wrong:** two derived finders, zero usages in `src/main` (account deletion
uses `existsBySellerUserIdOrBuyerUserId`, reviews use the pending-review query).
Each unused derived method is also an implicitly generated query plan nobody
reviews.

**Fix:** delete both.

## D-3 — `CloudinaryService.uploadImage` wrapper is unused (Low)

**Where:** `service/CloudinaryService.kt:31` — `fun uploadImage(file) = uploadImageAsset(file)?.url`.

**What's wrong:** every caller uses `uploadImageAsset` (needs `publicId` for
rollback/GC). The wrapper discards `publicId`, which is exactly the mistake the
media-cleanup redesign removed from `PostService` — leaving it around invites its
reintroduction.

**Fix:** delete the wrapper.

## D-4 — `JwtService.generateToken(extraClaims, userDetails)` overload is unused (Low)

**Where:** `service/JwtService.kt:39-43`.

**What's wrong:** only `generateToken(userDetails)` / `generateToken(user)` are
called (`AuthController`, `WhatsAppQrLoginService`). The `extraClaims` overload is
dead and, worse, its claims map bypasses the `uid`/`ver` discipline by letting
callers inject arbitrary claims.

**Fix:** delete it (or make `buildToken` the only extension point and keep claims
non-injectable).

## D-5 — Overlapping password-reset token purges (Low)

**Where:** `repository/PasswordResetTokenRepository.kt` — `deleteExpiredTokens` (used
once, in `PasswordResetService.resetPassword`) vs `deleteExpiredOrUsed` (used by
`CredentialCleanupScheduler`).

**What's wrong:** two methods whose predicates overlap (expired ⊂ used-or-expired),
split across a request path and a scheduler. The request-path purge is also
opportunistic: it only runs when someone happens to reset a password. Two nearly
identical `@Modifying @Query` blocks must be kept in sync.

**Fix:** keep `deleteExpiredOrUsed` only; let the scheduler own cleanup (it runs
every 5 min) and drop the call from `resetPassword` — or keep it, but call the same
method.

## D-6 — `migration_sale_quarantine` / `migration_review_quarantine` in the canonical schema (Low)

See [F-5](02-schema-and-migration-findings.md). Zero application references; keep
them as V2 migration artifacts, remove from `schema.sql`/`schema.dbml` (or document
a reconciliation procedure).

## D-7 — `WhatsAppQrLoginService.normalizePhone` duplicates `IdentityNormalizer.phone` (Low)

**Where:** `WhatsAppQrLoginService.kt` (private `"+" + phone.replace(Regex("\\D"), "")`)
vs `service/IdentityNormalizer.kt` (`"+" + value.filter(Char::isDigit)`).

**What's wrong:** byte-for-byte equivalent semantics, two implementations. The
private copy predates `IdentityNormalizer` and was never migrated. If normalization
ever changes (E.164 max length, country-code rules), the QR flow silently diverges
from registration/lookup and `findByPhoneNumber` misses.

**Fix:** delete the private copy, call `IdentityNormalizer.phone` (this also gives
V-10 one choke point to validate input).

## D-8 — `PostService.normalizePhone` one-line delegation (Low)

**Where:** `PostService.kt` — `private fun normalizePhone(phone: String) = IdentityNormalizer.phone(phone)`,
used once (`renewPost`).

**What's wrong:** a private alias for a public object method — pure noise; readers
must hop to learn that phone normalization is shared.

**Fix:** inline the call.

## D-9 — `expireStaleSessions` runs twice as often as intended (Low)

**Where:** `WhatsAppQrLoginService.expireStaleSessions` —
`@Scheduled(fixedRate = 300_000)` **and** `CredentialCleanupScheduler.cleanExpiredCredentials`
— `@Scheduled(fixedRate = 300_000)` which itself calls
`whatsAppLoginSessionRepository.expireStaleSessions(now)` (plus the other sweeps).

**What's wrong:** the same `UPDATE … WHERE status = PENDING AND expires_at < :now`
fires from two schedulers every 5 minutes. Harmless (idempotent) but pure duplicate
work and duplicate log/lock traffic, and it signals unclear ownership of the
cleanup concern (three jobs now touch session state: this one,
`expireUnclaimedCompletedSessions`, `deleteOldSessions`).

**Fix:** keep session lifecycle entirely in `CredentialCleanupScheduler` and delete
the `@Scheduled` method on the service (or vice versa — one owner).

## D-10 — `PostMediaRepository.findByPost_postId` naming inconsistency (Low)

**Where:** `repository/PostMediaRepository.kt:7` (`findByPost_postId`) vs `:8-11`
(`findByPostPostIdIn`, `findByPostPostIdInOrderByPostPostIdAscIsCoverDescDisplayOrderAscIdAsc`).

**What's wrong:** the underscore form (`findByPost_postId`) is a legacy traversal
style; the other three use the same property path without underscores. Same
repository, same path, two conventions — a small but real readability tax and a
trap for anyone generating new finders by copy.

**Fix:** rename to `findByPostPostId` in one commit (single caller:
`PostService.findOrderedPostMedia`).

## D-11 — `notification_outbox.payload` forces dummy empty strings (Low)

**Where:** `NotificationOutboxService.enqueueRegisteredUser` /
`enqueueAccountDeleted` pass `payload = ""` to satisfy `payload TEXT NOT NULL`.

**What's wrong:** two of six notification types have no message content (the
recipient is the payload for the bot endpoints), so every such row stores `""` in a
`NOT NULL TEXT` column. It misdocuments the schema ("payload contains the message")
and the constraint fights the domain.

**Fix:** make `payload` nullable (or store a small JSON envelope with a `type`
discriminator) and document that bot registration/deletion rows carry data in
`recipient` only.

## D-12 — `WhatsAppLoginSession.id` vs `sessionId`: two UUIDs for one entity (Low)

**Where:** `entity/WhatsAppLoginSession.kt` — `id UUID` (JPA `@Id`) **and**
`sessionId UUID` (unique), both freshly generated at construction.

**What's wrong:** `id` has no external consumer (grep: only JPA identity and test
setup); `sessionId` is the real lookup/unique key, `login_token`/`claim_token` are
the real secrets. Two parallel random identifiers per row invite mix-ups (a query
against `id` when `sessionId` was meant — both look like UUIDs) and waste a unique
constraint.

**Fix:** `@Id sessionId` (drop `id`) or document the internal/external split in the
entity. Low priority — but see V-4: whichever identifier remains should be treated
as a secret.

## D-13 — `EmailVerificationService.resendCode` double-loads the user (Low)

**Where:** `resendCode` does `findByEmail(…)`, checks `isEnabled`, then calls
`sendCode(email)`, which does `findByEmail(…)` **again**.

**What's wrong:** two identical primary-key-equivalent lookups per resend, and two
places that can disagree about the "No account found" error. Trivial, but it is the
kind of duplication that produced the bigger Q-2 list.

**Fix:** pass the loaded `User` into a private `sendCode(user)`.

## D-14 — `Post` entity defaults hide construction errors (Low)

**Where:** `entity/Post.kt` — `val user: User? = null` (the seller!) plus
`title: String = ""`, `price: BigDecimal = BigDecimal.ZERO`, `description: String = ""`.

**What's wrong:** the seller association is typed nullable-with-null-default even
though `seller_user_id` is `NOT NULL` and every code path (`post.user!!`, DTO
mapping) assumes it exists — a `Post()` built without a user compiles fine and dies
at flush with a DB error. The placeholder `title`/`price`/`description` defaults
similarly construct schema-violating rows (`title = ""` fails `chk_posts_title_nonblank`
at flush, not at compile site).

**Fix:** drop the defaults; make `user: User` non-nullable and the required fields
constructor-required (JPA doesn't need defaults here — the entity is never built
"empty").

## D-15 — Deliberate-but-noteworthy redundancies (Info)

Not dead, but listed so they aren't "cleaned up" by mistake:

- `posts.is_sold`/`status`/`buyer_user_id`/`sold_at` duplicate `sales.*` —
  **actively queried** by all feed/search paths and synchronized by
  `chk_posts_sale_state` + `enforce_post_sale_consistency`. Redundant by design;
  removing it means rewriting every listing query.
- `sales.sold_at` vs `sales.created_at` — different semantics (sale time vs record
  time/backfill); both are used or usable. Keep.
- `reviews.reviewer_user_id`/`reviewee_user_id` are derivable from
  `sale + direction` but power `idx_reviews_reviewee_direction_created_at` profile
  queries and are trigger-enforced. Keep.
- `favourites.id` surrogate key vs the logical `(user_id, post_id)` key — fine
  (JPA ergonomics), the unique constraint carries the invariant.
