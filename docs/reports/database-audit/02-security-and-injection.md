# Security and injection findings

[Report index](README.md)

## S01 — Accepted fallback secrets can compromise every account

**Severity: Critical if deployed with fallback values. Confidence: source-confirmed; actual deployment configuration not inspected.**

**Evidence:** [src/main/resources/application.properties:27](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/application.properties:27>), [src/main/resources/application.properties:53](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/application.properties:53>), [src/main/kotlin/com/studentstorefront/service/JwtService.kt:36](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/JwtService.kt:36>), [src/main/kotlin/com/studentstorefront/controller/WhatsAppQrLoginController.kt:37](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/controller/WhatsAppQrLoginController.kt:37>).

The JWT secret has a valid, repository-known fallback, rather than requiring an environment secret. The bot API key also has a known fallback. Possession of the signing key permits minting a token for an existing email; authorization then comes from that database user, including ADMIN. Possession of the bot key permits confirming a caller-created login session using an asserted registered phone number, then polling and claiming the resulting JWT. Public post responses expose seller contact details, making account selection easier. This attack does not require SQL injection.

**Fix:** require distinct high-entropy JWT and bot secrets outside an explicit disposable test profile; fail startup on missing/placeholder values. Scope bot credentials to separate listing/lookup/login operations. Rotate deployed defaults if used; invalidate existing sessions/tokens after rotation. Do not merely change the committed default string.

**Acceptance:** production configuration with missing secrets fails startup; arbitrary bot phone confirmation without the scoped login credential fails; tokens signed with the previous/default key are rejected.

## S02 — JWT authentication ignores disabled state and binds identity to reusable email

**Severity: High. Confidence: reproduced over HTTP.**

**Evidence:** [src/main/kotlin/com/studentstorefront/config/JwtAuthenticationFilter.kt:39](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/config/JwtAuthenticationFilter.kt:39>), [src/main/kotlin/com/studentstorefront/service/JwtService.kt:45](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/JwtService.kt:45>), [src/main/kotlin/com/studentstorefront/service/CustomUserDetailsService.kt:19](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/CustomUserDetailsService.kt:19>), [src/main/kotlin/com/studentstorefront/service/UserService.kt:259](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/UserService.kt:259>).

`CustomUserDetailsService` correctly populates `disabled(!user.isEnabled)`, but the filter bypasses an authentication provider and directly constructs an authenticated token. `isTokenValid` checks only matching username and expiry. A correctly signed token for a disabled fixture account returned HTTP 200 from `/api/auth/me`.

JWT subject is the mutable email. After account A changes email or is deleted, another account B can acquire the old email. A's unexpired token is then resolved to B's row and privileges. In the isolated app, the same token first identified user 2 and later identified a replacement user 7, even though the replacement was disabled. The test used an explicitly configured audit signing key; it demonstrates validation semantics, not discovery of a real secret.

Password changes/resets also do not invalidate prior JWTs. A stolen session can remain useful for the configured 24-hour lifespan after a password reset.

**Fix:** bind the subject to immutable `user_id`; check enabled/locked/expired account state explicitly; add a user session version or issued-before revocation check, incremented on sensitive changes. Identity deletion/recreation must never inherit old sessions. For credential updates require appropriate recent authentication and revoke reset/QR credentials as well.

**Acceptance:** disabled users receive 401/403; password reset invalidates older tokens; deleting/reusing an email and changing/reusing an email both leave old tokens unusable.

## S03 — Changing a verified email bypasses verification and recent authentication

**Severity: High. Confidence: reproduced.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/UserService.kt:114](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/UserService.kt:114>), [src/main/kotlin/com/studentstorefront/service/UserService.kt:259](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/UserService.kt:259>), [src/main/kotlin/com/studentstorefront/dto/update/UserUpdateDTO.kt:9](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/dto/update/UserUpdateDTO.kt:9>).

An authenticated user can replace email, phone, and password. The copy operation preserves `isEnabled=true`; there is no pending email, ownership challenge, old-password confirmation, or recent-login check. A verified user can adopt any unused syntactically valid university address without access to its mailbox. Login with the updated email still uses their chosen password. The test changed a verified fixture's email successfully and read `is_enabled=true` directly afterward.

This defeats the stated campus verification guarantee and lets a stolen JWT make durable credential changes. The domain regex alone verifies a string, not mailbox ownership.

**Fix:** keep the old verified email active until a separately stored pending email is verified. Require recent authentication for email/password/phone changes; revoke sessions and outstanding credentials appropriately. Send change notifications after commit. Track verified identity separately from account-enabled state.

**Acceptance:** changing an email cannot immediately make it an authenticated identifier; wrong/expired change codes fail; the old mailbox/identity lifecycle is explicit; a merely stolen stale JWT cannot reset credentials.

## S04 — Phone uniqueness is treated as ownership verification

**Severity: Medium, high for identity trust. Confidence: source-confirmed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/UserService.kt:183](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/UserService.kt:183>), [src/main/kotlin/com/studentstorefront/service/UserService.kt:250](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/UserService.kt:250>), [src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:70](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:70>).

Registration and updates normalize a caller-provided phone and check uniqueness but never prove phone ownership. Email verification enables the entire account, after which WhatsApp login trusts the phone association. A university email holder can reserve someone else's currently unregistered phone, misdirect contacts, and prevent that owner from linking their number. The legitimate phone owner may then log into the reserving account through WhatsApp. Existing unique phone ownership prevents simply overwriting a phone already linked to another account; this finding is not a claim that uniqueness is absent.

**Fix:** verify phone ownership through an authenticated WhatsApp challenge before enabling phone login or publishing that number. Store pending and verified phone state separately; provide a recovery policy. Normalize emails consistently as well: current equality/uniqueness is case-sensitive and local-part casing can create duplicate representations of campus identities.

**Acceptance:** an unverified number cannot be used for login/contact assertions; canonical phone/email variants resolve consistently; existing verified owners cannot be displaced through registration recycling.

## S05 — “One-time” credentials can be consumed concurrently

**Severity: High. Confidence: QR race reproduced; reset/verification races source-confirmed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:93](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:93>), [src/main/kotlin/com/studentstorefront/service/PasswordResetService.kt:38](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PasswordResetService.kt:38>), [src/main/kotlin/com/studentstorefront/service/EmailVerificationService.kt:34](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/EmailVerificationService.kt:34>), their repositories, and the absence of `@Version` or locking queries.

Each flow reads an unused/completed record, checks it, performs the action, and later updates `used`/`status`. Multiple transactions can pass the same check. `@Transactional` gives atomic commit/rollback; it does not make this sequence exclusive under the configured Read Committed isolation. PostgreSQL describes these concurrent visibility rules in its [transaction-isolation documentation](https://www.postgresql.org/docs/current/transaction-iso.html).

**Observed:** eight concurrent requests with one completed QR claim token returned six HTTP 200 responses and two HTTP 500 responses. The two failures logged `NoSuchElementException`; their specific cause was not established. The six successes alone disprove one-time consumption. Sequential replay checks in existing tests do not cover this race.

A reset race can allow two new-password writes from the same token, with the final writer winning. Verification can double-consume and emit duplicate notifications. Concurrent QR confirmation can overwrite the selected user/claim token before consumption.

**Fix:** lock the credential row during validation and state change, or perform a conditional update such as `UPDATE ... WHERE status='COMPLETED' AND ... RETURNING ...`; issue a token only if one row was claimed. Bind all eligibility/deadline conditions to that atomic operation. Define stable replay responses. Lock or version user updates to prevent stale account copies overwriting other security changes.

**Acceptance:** simultaneous claim/reset/verify requests produce exactly one successful consume; no duplicate successful JWT exchanges, lost password updates, or unexpected 500s.

## S06 — Stored listing URLs become unrestricted bot-side requests

**Severity: High. Confidence: source chain plus local sink reproduction; normal bot startup currently blocked by C01.**

**Evidence:** [src/main/kotlin/com/studentstorefront/dto/request/PostRequestDTO.kt:18](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/dto/request/PostRequestDTO.kt:18>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:269](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:269>), [bot/src/shopping/WhatsAppShoppingChat.js:298](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/shopping/WhatsAppShoppingChat.js:298>).

An authenticated user supplies `imageUrlList`; the backend stores the strings and returns them publicly. When a shopper requests details, the bot passes the first URL to `MessageMedia.fromUrl(imageUrl, { unsafeMime: true })` and sends the fetched bytes to the requesting chat. There is no approved-host check, private-IP restriction, redirect validation, size cap, or request timeout at this call site.

The installed helper fetches the URL server-side and buffers the response. A local HTTP stub on loopback returned `text/plain` containing `AUDIT_LOCAL_ONLY`, and the helper accepted it. No real internal service was contacted and no WhatsApp message was sent. The public [upstream helper source](https://docs.wwebjs.dev/structures_MessageMedia.js.html) documents the same behavior, but the installed source was inspected too.

After the bot startup defect is fixed, a listing can induce internal-network requests and potentially relay accessible content. Large or slow responses can consume memory/connections. Whether a particular internal target is reachable depends on the bot host and network, not on SQL permissions.

**Fix:** use provider-owned asset identifiers or restrict media to approved HTTPS origins. Validate resolved addresses and every redirect; block loopback/private/link-local destinations, set byte/time limits, and enforce allowed image types. Prefer controlled ingestion to arbitrary remote fetching. Repair C01 without overlooking this latent vulnerability.

**Acceptance:** controlled private-address and redirect tests are refused; oversized/non-image responses are refused; approved hosted images still work.

## S07 — Upload filenames control temporary filesystem paths

**Severity: High. Confidence: source-confirmed; no traversal write was executed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/CloudinaryService.kt:29](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/CloudinaryService.kt:29>), especially line 42.

The helper constructs `java.io.tmpdir + '/' + file.originalFilename`, opens it with `FileOutputStream`, and later deletes it. A crafted filename containing `../` can escape the intended temporary directory. Two uploads using the same ordinary filename also share a path: one request can overwrite or delete another request's file. An existing writable file can be truncated before Cloudinary is called. The reachable filesystem scope is the application OS account's permissions; remote code execution is not established by this review.

Failures leave temporary files because deletion is not in `finally`; stream handling is manual. The issue exists independently of whether Cloudinary subsequently accepts the image.

**Fix:** create a unique server-generated temporary file with a controlled suffix; never use original filenames as paths. Use structured resource cleanup and `finally` deletion, or upload a bounded byte stream directly. Validate content and count/size limits before writing.

**Acceptance:** traversal filenames cannot affect paths outside a dedicated temp location; simultaneous identical filenames use different files; upload failure leaves no orphan temporary file.

## S08 — Rate limiting is bypassable and omits registration

**Severity: Medium; compounds credential abuse and resource exhaustion. Confidence: HTTP bypass reproduced.**

**Evidence:** [src/main/kotlin/com/studentstorefront/config/RateLimitFilter.kt:22](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/config/RateLimitFilter.kt:22>), [src/main/kotlin/com/studentstorefront/config/RateLimitFilter.kt:33](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/config/RateLimitFilter.kt:33>), [src/main/kotlin/com/studentstorefront/config/RateLimitFilter.kt:70](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/config/RateLimitFilter.kt:70>).

The limiter trusts the first caller-supplied `X-Forwarded-For` value. In the local app, the 16th claim request received 429, but supplying a new forwarded address immediately returned the normal 410. This is exploitable where the edge does not replace untrusted forwarded headers. The cache has time expiry but no maximum size, so forged addresses also allocate buckets. Limits are process-local and multiply across instances.

Registration is absent from the protected path map: repeated pending registrations generate BCrypt work, database writes, and verification emails, and can repeatedly invalidate another pending signup. No per-code failed-attempt counter protects the six-digit verification space; distributed/IP-spoofed attempts bypass the intended defense. GET polling/search and reset-password also have no route-specific cap.

**Fix:** trust forwarded identity only from configured proxies; enforce per-account/token and per-IP budgets; include registration/reset/polling; cap cache capacity or use a shared limiter with bounded cardinality. Lock a verification challenge after a small number of failures rather than relying on IP throttling alone.

**Acceptance:** arbitrary forwarded headers do not reset budgets; several app instances share sensitive-operation limits; one challenge cannot be guessed indefinitely; repeated registration is bounded.

## S09 — Public listing reads expose archived records and contact details

**Severity: Medium privacy/policy issue. Confidence: reproduced.**

**Evidence:** [src/main/kotlin/com/studentstorefront/config/SecurityConfig.kt:32](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/config/SecurityConfig.kt:32>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:98](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:98>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:358](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:358>).

All post GET routes are public. `getAllPosts` uses unrestricted `findAll`; get-by-ID and user-listing routes also return archived/sold posts. Mapping unconditionally adds seller email and WhatsApp phone. The isolated anonymous `/api/posts` response included an archived fixture and both contact fields. Buyer details were correctly omitted for an anonymous request.

Publishing seller contact may be intentional, especially given the current uncommitted contact-field work. The concrete defect is that visibility/retention rules are not consistently enforced and the docs claim public queries use ACTIVE. Archive does not hide a record or its contact details. A crawler can enumerate profiles/listings without joining the campus marketplace.

**Fix:** define public-contact consent and listing visibility explicitly; use active public projections, authenticated contact access if required, and owner/admin endpoints for archived data. Avoid presenting archive as a privacy mechanism unless it actually restricts reads.

**Acceptance:** anonymous/public/profile routes follow a single documented visibility policy, while owners retain access to their archived items. Public buyer redaction remains covered.

## S10 — Unhandled exceptions expose implementation details

**Severity: Medium. Confidence: reproduced.**

**Evidence:** [src/main/kotlin/com/studentstorefront/exception/GlobalExceptionHandler.kt:30](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/exception/GlobalExceptionHandler.kt:30>), [src/main/kotlin/com/studentstorefront/exception/GlobalExceptionHandler.kt:52](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/exception/GlobalExceptionHandler.kt:52>), [src/main/kotlin/com/studentstorefront/controller/AuthController.kt:48](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/controller/AuthController.kt:48>).

The catch-all exception handler returns `ex.message`. An invalid sort produced HTTP 500 and `No property 'notAField' found for type 'Post'`. Other ORM/provider errors can reveal implementation details. Registration email failures explicitly return underlying SMTP/root-cause messages. Integrity errors are partly sanitized but classified by loose message substrings; a length/check failure can be mislabeled as a duplicate record, and missing entities are generally 400 instead of 404.

**Fix:** return stable public error codes/messages; log full causes internally with correlation IDs. Map invalid sort/input to 400, missing rows to 404, authorization to 403, and named unique/foreign-key/business conflicts to specific 409 responses. Avoid logging secrets or complete entities.

**Acceptance:** malformed queries and provider failures never return SQL, host details, or stack internals; client contracts distinguish validation, missing records, and conflicts.

## S11 — Database deployment defaults grant excessive access

**Severity: High if used on an exposed host; conditional.**

**Evidence:** [docker-compose.yaml:1](</home/zach/Desktop/productivity/Programming/SDE Sales App/docker-compose.yaml:1>), [src/main/resources/application.properties:9](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/application.properties:9>).

Compose publishes `5432:5432` without a loopback address and uses a known placeholder password. The official image's configured initial `POSTGRES_USER` is the bootstrap database superuser, as specified in the [official image documentation](https://raw.githubusercontent.com/docker-library/docs/master/postgres/README.md); the application's default username is that same account. There is no separate runtime DML role and migration owner in the repository configuration. The unpinned `postgres` image also differs from PostgreSQL 15 in CI/tests (C06).

**Fix:** bind local-only development databases to loopback or an internal network; provision strong credentials; separate migration ownership from runtime CRUD permissions; pin a supported major version. Establish backups and restore drills outside this review's scope. Actual deployed ACLs, TLS, backups, and firewall rules were not inspected, so their absence is not asserted.

**Acceptance:** the runtime role cannot create/drop schema objects or act as superuser; external clients cannot reach a development DB unintentionally; restore and version-upgrade procedures are tested.

## Injection and authorization sink matrix

| Entry point / sink | Observed protection | Result |
|---|---|---|
| Search `q`, category, repository deletes/updates | Bound JPA/JPQL parameters; LIKE escapes applied in services. | No SQL injection found. A quoted SQL-shaped search returned a normal empty page; source review is stronger evidence than this single probe. |
| `page`, `size`, `sort` | Spring Pageable/property resolution; no `JpaSort.unsafe` usage found. | No arbitrary SQL sorting found. Invalid sort leaks details; valid expensive/nested properties need an allowlist. Spring's [query-method documentation](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html) explains sort handling. |
| JSON user/post/review DTOs | Explicit DTO fields, controller roles, service ownership checks; review direction derived server-side. | No ordinary-user role mass assignment or arbitrary-reviewee write found. Bot creation intentionally permits supplied user IDs and needs narrower trust. |
| Stored image URL → bot fetch | No destination policy; generic URL annotations ineffective in observed runtime. | SSRF/remote-content ingestion path, S06. |
| Multipart filename → local file | Raw concatenation of original filename. | Traversal/collision write path, S07. |
| Listing/review/name text → HTML | Reviewed templates use text/attribute binding; no `x-html`, `innerHTML`, or dynamic eval sink found in these paths. | No stored script execution demonstrated. This does not certify every frontend or dependency. |
| WhatsApp/LLM output → listings | Selected content fields normalized; user ID comes from phone lookup, not model-selected ownership. | Content/prompt manipulation possible, but no model-to-SQL execution path found. Preserve this boundary. |
| Bot webhook body | API key checked before message/submission operations. | No unauthenticated webhook send found; shared-key scope and defaults remain concerns. |

Passwords and verification codes are BCrypt-hashed; random reset tokens are SHA-256-hashed. These protections should be preserved. QR UUIDs are high-entropy bearer secrets rather than sequential IDs; not binding them to IP is not automatically a vulnerability. Hashing their stored lookup values, minimizing logs, and shortening retention reduce database-read exposure without relying on unstable IP binding.
