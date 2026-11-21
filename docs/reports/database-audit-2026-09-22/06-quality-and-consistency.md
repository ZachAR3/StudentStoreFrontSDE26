# Program quality and consistency (`Q-*`)

[Report index](README.md)

---

## Q-1 — `SAFE_MESSAGES` allowlist silently degrades real error messages (Low, user-visible bugs)

**Where:** `exception/GlobalExceptionHandler.safeInvalidRequestMessage` — messages
not in the `SAFE_MESSAGES` set (or matching its four `startsWith` prefixes) collapse
to `"Invalid request"`.

**What's wrong — specific messages that are thrown but absent from the set**, so the
client never sees them:

| Thrown from | Message | Client actually sees |
| --- | --- | --- |
| `PostService.validatePostInput`, `updatePostEntity` | `Description must be between 10 and 1000 characters` | `Invalid request` |
| `PostService.normalizePrice` | `Price must have at most two decimal places` | `Invalid request` |
| `PostService.normalizeImageHashes` | `Image hash must be SHA-256` | `Invalid request` |
| `PostService.savePostMedia`, `saveUpdatedPost`, `replacePostMediaWithUrls` | `Duplicate images are not allowed` | `Invalid request` |
| `PostService.createPostWithImages`, `resolveUpdatedMediaUrls` | `Cover index is out of range` | `Invalid request` |
| `PostService.replacePostMedia` | `Cover index must not be negative` | `Invalid request` |
| `ReviewService.findReviewableSale`, `getReviewContext` | `This sale is awaiting buyer confirmation` | `Invalid request` |
| `ReviewService.createReview` | `Sale not found with id: N` (the prefix check looks for `No sale found`) | `Invalid request` |
| `UserService.deleteOwnAccount` | `Incorrect password` | `Invalid request` |
| `EmailVerificationService` (several) | `Invalid verification code` variants beyond the exact set strings | varies |

**Why it's wrong:** the allowlist is a second, hand-maintained copy of the services'
error vocabulary that has already drifted from it. Users get opaque errors for
perfectly ordinary mistakes (bad cover index, short description, wrong delete
password), and every new message is a silent regression unless someone remembers
to edit `SAFE_MESSAGES`. The `startsWith` prefixes (`"No sale found"` vs the thrown
`"Sale not found"`) show the two lists were never reconciled.

**Fix:** invert the model — throw typed exceptions
(`ValidationException(code, publicMessage)`), and have the handler serialize
`code`+`message` directly; sanitize only truly dynamic content (ids ok, secrets not).

## Q-2 — Copy-pasted policy helpers (Low)

**What's wrong — identical or near-identical logic living in several places:**

- `matchesBotKey()` — 3 copies (Post/User/WhatsAppQrLogin controllers).
- `escapeLike()` — 2 copies (PostService/UserService) (see I-2).
- `safePageable()` — 2 similar-but-different copies with separate sort allowlists
  (PostService/UserService) (see I-3).
- `normalizePhone` — 2 copies + 1 delegation (D-7/D-8).
- `mapUserToPublicResponseDTO` — 3 copies (PostService/ReviewService/UserService).
- `getCurrentUser()` via `SecurityContextHolder … findByEmail(IdentityNormalizer…)`
  — 4 near-copies (PostService/ReviewService/FavouriteService/UserService/SaleService's
  `currentUser`), each with slightly different failure behavior
  (`ResourceNotFoundException` vs `IllegalArgumentException` vs `AccessDeniedException`
  for the same "not authenticated" condition).

**Why it's wrong:** the same rule diverges over time (the `getCurrentUser` variants
already return different HTTP statuses for one condition), and security-relevant
policy (bot key, sort allowlist) becomes three places to forget.

**Fix:** extract a small `security/SecurityUtils.kt` (currentUser/currentUserId/
isAdmin), `persistence/QuerySafety.kt` (escapeLike + safePageable builder), and one
bot-key filter (V-11).

## Q-3 — `UserService` wires required dependencies as optional (Medium)

**Where:** `service/UserService.kt` —
`@Autowired(required = false) private var saleRepository: SaleRepository? = null`
and `mediaCleanupService: MediaCleanupService? = null`, with logic depending on
nullability: `val user = if (saleRepository != null) … else null`,
`hasSales() = saleRepository?.existsBy… == true`.

**What's wrong:** `SaleRepository` is not optional for correct deletion semantics —
it decides **hard-delete vs anonymize**. If the bean ever fails to inject (renamed,
excluded from scanning, test slice), `hasSales()` silently returns `false` and
`deleteUser`/`deleteOwnAccount` **hard-delete a user with sales history**. The only
thing that saves the data is the accidental backstop of `sales.buyer_user_id` FK
(NO ACTION) → the request 500s with a raw FK violation instead of the mapped error.
So a wiring problem converts a safe anonymize path into a failed/attempted data loss.

**Why it's wrong:** optionality is a statement about business logic ("this
dependency may not exist"), but here it is being used as a wiring convenience.
Required dependencies should be constructor-injected so misconfiguration fails at
startup. (The same pattern on `notificationOutboxService`/`emailService` in
`UserService`/`EmailVerificationService`/`PasswordResetService` at least degrades to
a working fallback path; `saleRepository`/`mediaCleanupService` do not.)

**Fix:** constructor-inject `SaleRepository` and `MediaCleanupService` as
non-nullable; keep only genuinely optional collaborators (notification fallbacks)
as nullable.

## Q-4 — Entity nullability/defaults disagree with the schema (Low)

**Where:** `entity/Post.kt` (`user: User? = null`, `title = ""`, `price = BigDecimal.ZERO`);
`entity/User.kt` (`registrationPending = !isEnabled` default couples two columns'
defaults at construction time).

**What's wrong:** see D-14 for the Post defaults. On `User`, encoding
`registration_pending = NOT is_enabled` as a *constructor default* means the
invariant is enforced only when the parameter is omitted — `User(isEnabled = true,
registrationPending = true)` compiles and is accepted by `ddl-auto=validate` (no DB
check links the two columns). The V2/V3 migrations separately "repair" the pair
with `UPDATE users SET registration_pending = NOT is_enabled` — three places encode
one rule.

**Fix:** derive `registrationPending` in a single factory (or a DB CHECK
`chk_users_pending_state CHECK (registration_pending = NOT is_enabled OR …)` if the
rule truly holds).

## Q-5 — Two time sources: entity constructors vs injected `Clock` (Low)

**Where:** entity defaults (`EmailVerificationToken.expiresAt = now + 15min`,
`PasswordResetToken.expiresAt = now + 30min`,
`WhatsAppLoginSession.expiresAt = now + 5min`, `createdAt = LocalDateTime.now(ZoneOffset.UTC)`)
vs services taking `clock: Clock` (`LocalDateTime.now(clock)`).

**What's wrong:** business policy (token TTLs, session TTL) is hidden in data-class
default constructors where the injected, test-controlled `Clock` cannot reach. Tests
can freeze service-side time but token rows still stamp themselves from the system
clock, so expiry edge behavior can't be tested deterministically (the old audit's
"test clock advances all state" acceptance criterion is unreachable while this
split exists). It also hides *who* sets `expiresAt` — `createPostEntity` passes it
explicitly, tokens don't.

**Fix:** make TTLs explicit at creation sites (services pass `now.plusMinutes(15)`
from their `Clock`), keep entity fields dumb.

## Q-6 — `data class copy()` on JPA entities (Low)

**Where:** `Post.copy(…)` in `renewPost`, `markAsSold`, `updatePostEntity`;
`User.copy(…)` in `recycleUnverifiedSeller`, `anonymizeUser`, `verifyCode`,
`updateUserEntity`, `resetPassword`.

**What's wrong:** `copy()` produces a detached twin carrying the same `@Id`/`@Version`,
re-attached via `save()` (merge). It works, but it bypasses normal dirty-checking
semantics: changes to the original managed instance are silently discarded, merge
costs an extra SELECT, and interaction with `@Version`/triggers depends on merge
semantics that are subtle (see F-11). It also copies **all** fields by default —
a new sensitive field added to `User` will flow into every `copy()` unless each
site is revisited.

**Fix:** prefer mutating `var` fields on the managed entity (the entities already
use `var` where mutation is intended) or explicit `apply { }`-style updates; if
`copy()` stays, keep the entities immutable and route all writes through named
factory methods.

## Q-7 — Review-flow errors are exceptions-of-wrong-type and swallowed (Medium)

**Where:** `ReviewService` — `findReviewableSale`/`getReviewContext` throw
`IllegalArgumentException("This sale is awaiting buyer confirmation")` (maps to 400
`INVALID_REQUEST`, and per Q-1 the message is lost); `resolveDirection` throws
`AccessDeniedException` inside `getPendingReviewContexts`, which wraps it in
`runCatching { … }.getOrNull() ?: return@mapNotNull null`.

**What's wrong:**

1. "Awaiting buyer confirmation" is a **domain state**, not a malformed request —
   409/422 with a stable code would let the frontend show "waiting for buyer"
   (which the sale-confirmation UI needs anyway).
2. `Sale not found with id: N` should be 404 (`ResourceNotFoundException`) like
   `PostService` does, not 400.
3. The `runCatching` swallow means any bug in `resolveDirection` (NPE on a
   half-migrated sale, future refactor) silently drops rows from `/api/reviews/pending`
   instead of failing loudly.

**Fix:** typed domain exceptions (ties into Q-1); replace `runCatching` with the
same participant derivation the query already encodes (the query guarantees
`current` is buyer or seller, so `resolveDirection` cannot legitimately fail there).

## Q-8 — The seller association is named `user` on `Post` but `seller` everywhere else (Low)

**Where:** `entity/Post.kt` (`val user: User?` → column `seller_user_id`),
`Sale.seller`, `PublicUserResponseDTO user`, REPOMAP ("`user`: Reference to a `User`
(the seller)").

**What's wrong:** one concept, three names (`Post.user`, `sales.seller_user_id`,
response field `user`), and `Post` additionally has `buyer` — so `post.user` vs
`post.buyer` reads symmetric even though the columns say `seller`/`buyer`. This is a
steady source of wrong-variable bugs (e.g. `post.user?.userId` vs `post.buyer?.userId`
appear side by side in `isVisibleToViewer`, `markAsSold`, `renewPost`).

**Fix:** rename the entity property to `seller` (column mapping already correct) in
one mechanical pass.

## Q-9 — Three schema sources of truth, one of them synthetic (Low)

**What's wrong:** the runtime schema is defined by V2+V3+V4+V5+V6+V7 (with V3
re-asserting V2 content "for installations that already recorded V2"), then
re-declared in `docs/database/schema.sql`, then re-declared again in
`schema.dbml`, and finally *also* partially encoded in JPA annotations
(`@Table(indexes=…)`, `uniqueConstraints`, `columnDefinition`). V3's existence
proves the model already failed once (V2 shipped without the invariants).
`ddl-auto=validate` covers only names/types.

**Why it's wrong:** every schema change now needs 3–4 synchronized edits (F-1
documents the current drift), and the "guarded additive" style of V3/V6/V7
(`IF NOT EXISTS` everywhere) means two databases that arrived by different upgrade
paths can hold differently-named constraints while both "pass" validation.

**Fix:** migrate → dump → diff as the documentation pipeline (F-1), and give CI a
job that migrates an empty DB and a V2-era DB and compares `pg_dump --schema-only`
output.

## Q-10 — `deleteUser` vs `deleteOwnAccount` duplicate flows with behavioral drift (Low)

**Where:** `UserService.deleteUser(userId)` vs `deleteOwnAccount(password)`.

**What's wrong:** the same lifecycle (password check aside) is implemented twice
with small divergences: `deleteUser` resolves the user via
`if (saleRepository != null) userRepository.findById(userId).orElse(null) else null`
(Q-3) while `deleteOwnAccount` uses `getCurrentUser()`; one calls
`userRepository.deleteById`, the other `userRepository.delete(user)`; only
`deleteOwnAccount` verifies a password (correct, but the asymmetry lives in
duplicated bodies). A policy change (e.g. outbox notification wording) must be made
twice.

**Fix:** one private `deleteAccount(user: User)` used by both entry points.

## Q-11 — Two delivery paths for verification/reset emails (Low)

**Where:** `EmailVerificationService`/`PasswordResetService`/`UserService`:
`if (notificationOutboxService != null) outbox… else AfterCommit.run { emailService… }`,
and the constructors take `notificationOutboxService: NotificationOutboxService? = null`.

**What's wrong:** the outbox path is retried/leased/purged (V-3) while the fallback
path is fire-and-forget after commit with a `runCatching` log. Same business event,
two reliability classes. Since `NotificationOutboxService` is an unconditional
`@Service` in this codebase, the fallback branch is effectively dead in production
but still reachable in tests/slices — so tests may exercise a delivery path
production never uses.

**Fix:** make the outbox mandatory (constructor-injected non-null) and delete the
direct-send fallback, keeping `EmailService` used only by `NotificationOutboxProcessor`.

## Q-12 — DTO/serialization inconsistencies (Low)

**Where:** `PostResponseDTO` — `@get:JsonProperty("isSold")` and
`@get:JsonProperty("isFavourited")` hand-fix two names while sibling fields rely on
default naming; `mediaUrls: List<String>` drops media identity/order/cover info that
`PostImageReferenceDTO` then has to reconstruct by URL string on update.

**What's wrong:** URL-as-identity is brittle: two different media rows with the same
URL (possible across posts via hash reuse) are indistinguishable in update payloads,
and `saveOrderedMediaPreservingIdentity`'s `associateBy { it.mediaUrl }` silently
collides if a post ever holds duplicate URLs (currently blocked by a `require`, so
latent). The hand-written `@get:JsonProperty` annotations also hint the JSON
contract is being steered case-by-case rather than by a configured naming strategy.

**Fix:** return media as structured items (`{id, url, isCover, displayOrder}`) and
reference media by `id` in update payloads; set one global Jackson naming strategy.

## Q-13 — Misc code-quality notes (Info)

- `passwordEncoder.encode(rawCode)!!` / `encode(request.password)!!` — `encode`
  returns non-null `String`; `!!` noise appears ~8 times.
- `PostRepository` mixes styles: `@Query` with fully-qualified enum literals
  (`com.studentstorefront.enums.PostStatus.ACTIVE`) — importing and using
  `PostStatus.ACTIVE` would compile-check and read better.
- `FavouriteService.addFavourite` loads the whole `Post` entity only to produce
  "Post not found" — a `existsById` would do; conversely this is *good* UX versus
  raw FK errors, so keep the check and shrink the fetch.
- `ReviewService` imports `PageRequest` twice (once fully-qualified inline:
  `org.springframework.data.domain.PageRequest.of(0, MAX_PENDING_REVIEWS)`).
- `UserService.mapToPrivateResponseDTO`/`mapToPublicResponseDTO` are `fun` (public)
  utility methods on a service — package-internal would suffice.
