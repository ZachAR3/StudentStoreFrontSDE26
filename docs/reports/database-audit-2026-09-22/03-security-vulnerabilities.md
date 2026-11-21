# Security vulnerabilities (`V-*`)

[Report index](README.md)

Each item: where it lives, how it is wrong/explained, and the fix. Strengths worth
preserving are listed at the end (V-12).

---

## V-1 — Rate limiter bypassable via leftmost `X-Forwarded-For` (High)

**Where:** `config/RateLimitFilter.kt` — `resolveClientIp()`:
`forwarded.split(",").first().trim()`.

**What's wrong:** when `request.remoteAddr` is a configured trusted proxy, the
filter takes the **leftmost** `X-Forwarded-For` entry as the client IP. In the
standard deployment (edge proxy *appends* the real client to any client-supplied
`X-Forwarded-For`), the leftmost entry is **attacker-controlled**. An attacker
sends `X-Forwarded-For: 1.2.3.4`, then `5.6.7.8`, … and each request lands in a
fresh rate bucket. All per-IP budgets become per-attacker-imagination: `/api/auth/register` (3/min),
`/api/auth/login` (5/min), `/api/auth/verify-email` (5/min),
`/api/auth/resend-verification` (3/min), `/api/auth/forgot-password`,
`/api/auth/reset-password`, and the WhatsApp session/claim endpoints.

**Why it matters for the database:** the 6-digit verification code has a 5-attempt
lockout *per token* (good) but the overall guessing budget is the IP rate limit. A
spoofed-IP attacker can request codes and guess across an unbounded number of IPs,
turning `email_verification_tokens` into a brute-forceable credential. The same
bypass enables credential stuffing against `users.password` and mass pending-account
recycling (see V-6). The DB-backed window table (`api_rate_limit_windows`) makes the
limiter *distributed* — the bypass is distributed too.

**Fix:** compute the client IP from the **rightmost untrusted** hop (or delegate to
Tomcat's `RemoteIpValve` with `internal-proxies`), and additionally key sensitive
limits by account/email and by token id, not only by IP.

---

## V-2 — `JwtService` silently falls back to a random signing key (High)

**Where:** `service/JwtService.kt` — `generatedFallbackKey = Jwts.SIG.HS256.key().build()`
and `getSignInKey()`: `if (secretKey.isBlank()) return generatedFallbackKey`.
`jwt.secret=${JWT_SECRET:}` defaults to empty. `SecretsConfiguration` enforces a
strong secret only for `prod`/`production` profiles or `REQUIRE_SECURE_SECRETS=true`.

**What's wrong:** with `JWT_SECRET` unset (any non-prod profile, or prod without the
flag/profile name), every application instance mints tokens with a **throwaway key
generated at startup**. Consequences: tokens die on restart (silent auth outage),
two instances can't validate each other's tokens (random 401s behind a load
balancer), and — most importantly — misconfiguration is invisible. A deployment
that *believes* it's running with a configured secret but has a typo'd env var is
indistinguishable from one that is working. There is also no key-rotation story
(`kid` header absent), so the only rotation path invalidates all sessions.

**Why it matters for the database:** the JWT is the gateway to every row-level
authorization rule (`assertOwnerOrAdmin`, buyer checks, admin endpoints). A signing
key failure mode should be loud, not a silent downgrade.

**Fix:** fail startup whenever the secret is blank outside an explicit `local`/`test`
profile (extend `SecretsConfiguration` instead of `getSignInKey` fallback); delete
the fallback key entirely.

---

## V-3 — Plaintext verification codes and reset links in `notification_outbox.payload` (Medium)

**Where:** `service/NotificationOutboxService.kt` (`enqueueVerificationEmail`
payload = raw 6-digit code; `enqueuePasswordResetEmail` payload = full reset URL
containing the raw UUID token), table `notification_outbox.payload TEXT NOT NULL`;
purge policy in `scheduler/NotificationOutboxProcessor.kt`:
`purgeDelivered(now.minusHours(1))`.

**What's wrong:** the token tables themselves are hashed (BCrypt code hash, SHA-256
token hash — good), but the outbox immediately re-introduces the **raw secret in
plaintext** and keeps it for at least an hour after successful delivery (rows are
purged only when `sent_at < now-1h`). Undelivered rows persist even longer: retries
back off up to 1 h and a permanently failing message (bad recipient, bot down) keeps
its live reset link in the table **indefinitely** with the failure reason in
`last_error`. `title`/`sellerName`/phone numbers are also stored in payloads —
PII-retention is undefined.

**Why it matters:** anyone with `SELECT` on the DB (backups, replicas, support
dumps, the course grader) can read a password-reset link that is valid for 30
minutes and an email code valid for 15 — the hashes in `password_reset_tokens`/
`email_verification_tokens` are bypassed entirely.

**Fix:** store only `token_id`/`code_id` in the payload and render the message at
delivery time from a short-lived, still-valid source (delete the outbox row the
moment the credential is consumed or expires); or encrypt payloads with a
deployment key. At minimum: purge on delivery (not 1 h later), cap total retry age,
and delete payloads when the credential dies.

---

## V-4 — QR login: `sessionId` is an unbound bearer capability (Medium)

**Where:** `service/WhatsAppQrLoginService.kt` (`pollSession` returns `claimToken`
to any caller with `sessionId`; `claimJwt` exchanges it for a full JWT);
`WhatsAppLoginSession.creatorIp` is stored at creation and **never checked again**.

**What's wrong:** `pollSession` is a public endpoint. Possession of `sessionId` —
which appears in the browser URL/polling traffic and is a bearer secret — yields
`claimToken`, which yields a JWT for the linked account. The schema clearly
*intended* binding (`creator_ip` column) but the runtime never compares it on poll
or claim, so a shared/leaked `sessionId` (shoulder-surfed QR page, proxy log,
browser extension, support screenshot) can be claimed from anywhere within the
5-minute windows. `phone_number` on `PHONE_NOT_LINKED`/`COMPLETED` rows is also
retained indefinitely-ish (until the 1-hour cleanup sweep) — see D-1.

**Why it matters:** this is the only login path that doesn't require the account
password, so its session-secret hygiene is the whole security argument.

**Fix:** enforce `creator_ip` (or a per-session poll nonce set at creation) on
`pollSession`/`claimJwt`; rate-limit by `sessionId` too; hash `login_token`/
`claim_token` at rest like the other tokens, or shorten retention.

---

## V-5 — Inconsistent user enumeration (Medium)

**Where:** `POST /api/auth/register` → `"Email already exists: …"` /
`"Phone number already registered: …"`; `POST /api/auth/resend-verification` →
`"No account found for this email"` (both via `GlobalExceptionHandler`'s
sanitized prefixes, so these strings **reach the client verbatim**); login →
`DisabledException` mapped to `"Please verify your email before logging in"` vs
unknown user → generic auth failure.

**What's wrong:** `POST /api/auth/forgot-password` correctly returns the neutral
"If that email exists, a reset link has been sent" — the other four endpoints
disclose existence and even state (verified vs pending). On a campus with a known
email pattern (`@constructor.university`) this enumerates the entire user base and
its pending/unverified state, feeding V-1/V-6 targeting.

**Fix:** make register/resend/verify/login responses uniform
("check your inbox"), move duplicate-registration detection to the verification
stage, and return one auth-failure message for unknown-user vs bad-password vs
disabled.

---

## V-6 — Pending-registration pre-hijack via phone-merge recycling (Medium)

**Where:** `service/UserService.kt` — `resolveRegistrationUser()` /
`recycleMergedUnverifiedAccounts()`: when a request's email and phone belong to two
*different* pending users, the **phone-owner's account is deleted**
(`clearUserDependencies` + `deleteById`) and the phone is attached to the
email-owner's recycled account.

**What's wrong:** registration is unauthenticated (3/min per IP — bypassable per
V-1). An attacker who knows a victim's phone number can repeatedly register with
the victim's phone + attacker emails, each time destroying the victim's in-flight
pending signup and re-binding the victim's number to an attacker-controlled
account. The victim can no longer complete registration with their phone, and their
number is now attached to an account whose mailbox the attacker controls. Once the
attacker's account is enabled, the victim's WhatsApp login (`confirmLogin` matches
purely on phone) would log into the *attacker's* account if the attacker ever
completes verification — a durable identity mix-up.

**Why it's wrong:** "refresh a pending registration" is a legitimate product rule
(REPOMAP documents it), but the merge direction destroys one party's record based
on unauthenticated input. Ownership of neither the email nor the phone is proven at
merge time.

**Fix:** never delete an account based on an unauthenticated request; only recycle
the *same* email's pending row. Resolve phone/email conflicts by rejecting the
registration ("phone pending on another signup — recover or verify it") or by
requiring phone proof (V-4's WhatsApp challenge) before attaching a phone to a
different account.

---

## V-7 — Anonymization placeholder phone can collide and looks real (Low)

**Where:** `service/UserService.kt` `anonymizeUser()`: `phoneNumber = "+999${user.userId}"`,
`email = "deleted-${user.userId}@invalid.local"`.

**What's wrong:** `users.phone_number` is `UNIQUE`. `+999<id>` is a *dialable-looking*
number in the accepted input space (`^\+?[0-9]{10,15}$` normalizes to `+` + digits),
so a real user could already hold `+999…` equal to a future anonymized id — the
anonymize `UPDATE` then dies with a unique violation *in the middle of an account
deletion*, leaving a half-deleted state for the caller. It also briefly appears as
a "contact" on preserved sale/review history (the DTOs gate phones behind
participant checks, mitigating exposure).

**Fix:** use a reserved, non-dialable pattern outside the accepted input space
(e.g. `+000-deleted-<id>`, stored through a dedicated sentinel constant), and/or
handle the unique violation by suffixing.

---

## V-8 — Integrity-error classification is an error-text oracle (Low)

**Where:** `exception/GlobalExceptionHandler.handleDataIntegrityViolation` —
`details.contains("email")`, `contains("phone_number")`, `contains("rating") ||
contains("check constraint")` over the raw chain of DB/ORM messages.

**What's wrong:** response semantics depend on PostgreSQL's English error text
(constraint/table names leak into the matching). A message containing "email" from
an unrelated failure maps to "An account with this email already exists" — a lie to
the client and a mislabel in support flows; localization or a driver upgrade can
silently change behavior. The trigram `contains("rating")` is especially arbitrary.

**Fix:** inspect `DataIntegrityViolationException.constraintName` (PG exposes it via
`getMostSpecificCause()` as `PSQLException.getServerErrorMessage().getConstraint()`)
and map **named** constraints (`uk_users_email`, `uk_users_pending_email`,
`chk_review_rating`, …) to messages. This is another payoff from F-1's naming
unification.

---

## V-9 — Verification-code check order wastes work and muddies lockout (Low)

**Where:** `service/EmailVerificationService.verifyCode()` — attempts check →
`passwordEncoder.matches` → **then** expiry check.

**What's wrong:** an expired token still gets a full BCrypt comparison (CPU cost on
an unauthenticated endpoint) before being rejected as expired; and a correct code
on an expired token returns "expired" without marking `used`, so the row lingers
until the cleanup scheduler deletes it. Minor, but the ordering is backwards:
expiry/used are cheap rejections and should come first. The 5-attempt lockout
(`MAX_ATTEMPTS`, `attempts` column) and `@Lock(PESSIMISTIC_WRITE)` on the lookup
are good and should stay.

**Fix:** check `used`, `attempts`, `expiresAt` before `passwordEncoder.matches`;
mark expired tokens `used` on sight.

---

## V-10 — `WhatsAppConfirmRequestDTO` is unvalidated (Low)

**Where:** `dto/request/WhatsAppConfirmRequestDTO.kt` — `loginToken`/`phoneNumber`
plain strings, no `@NotBlank`/pattern; `WhatsAppQrLoginService.normalizePhone`
turns any garbage into `+` + digits (empty input → `"+"`).

**What's wrong:** bot-supplied `phoneNumber: ""` or `"abc"` stores `"+"` as
`whatsapp_login_sessions.phone_number` on `PHONE_NOT_LINKED` rows. The column is
`VARCHAR(32)` and unconstrained, so junk accumulates; a `+` value also passes
naive phone comparisons elsewhere. (The endpoint is bot-key protected, so exploit
value is low — it's a data-hygiene contract gap.)

**Fix:** add `@Pattern("^\\+?[0-9]{10,15}$")` (same as `UserRequestDTO`) and reject
non-conforming numbers before touching the session row.

---

## V-11 — Bot-key check duplicated three times (Info)

**Where:** `PostController`, `UserController`, `WhatsAppQrLoginController` — three
byte-identical `matchesBotKey()` copies (constant-time `MessageDigest.isEqual`,
blank-key rejection — both correct).

**What's wrong:** not a vulnerability today, but a drift hazard: scoping the bot key
(per-endpoint keys, expiry, rate limits) must be edited in three places, and the
next bot endpoint may forget the check entirely — its route is `permitAll` in
`SecurityConfig`.

**Fix:** one `X-Bot-Api-Key` filter or `AuthenticationProvider` guarding
`/api/**/bot/**`, `/api/users/by-phone`, `/api/auth/whatsapp/confirm`.

---

## V-12 — Security properties worth preserving (Info)

The hardening rework got these right — don't regress them:

- Tokens at rest: BCrypt for codes/passwords, SHA-256 for reset tokens (V-3 is only
  about the outbox copy).
- `JwtService.isTokenValid` checks `uid`, `ver` (`token_version`), email match,
  `isEnabled` and expiry — password/phone changes and anonymization revoke old JWTs.
- All credential-consuming lookups use `@Lock(PESSIMISTIC_WRITE)`
  (`findByTokenHash`, `findTopBy…Verification…`, `findBySessionId/LoginToken/
  ClaimToken`, outbox claim) — the one-shot race from the previous audit is closed.
- `users`/`posts` carry `@Version` optimistic locking; `markAsSold`/`confirmPurchase`
  take pessimistic locks; `sales.post_id` UNIQUE turns double-mark-sold into a
  clean conflict.
- `PostResponseDTO` exposes seller `email`/`whatsappPhone` and buyer identity only
  to owner/buyer/admin (`canViewBuyer`/`canViewContact`).
- `MediaUrlPolicy.validate` enforces HTTPS, no userinfo, no localhost/`.local`, and
  resolves DNS to reject loopback/private/link-local addresses (SSRF posture for
  stored media URLs — residual rebinding caveat in I-5).
- `SecretsConfiguration` fails startup on weak secrets in prod (partially — see V-2),
  rate limits cover the auth surface including `verify-email` and `resend-verification`,
  and `GlobalExceptionHandler` returns correlation IDs instead of stack traces.
