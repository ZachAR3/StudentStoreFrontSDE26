# Student-Store Front: Full-Stack Audit

Audit date: 2026-11-19. This audit covers the **working tree as found**, which includes 121 uncommitted or untracked paths and new migrations V3 to V7. No application code was changed; this file is the only addition.

Each finding has:
- an ID
- a severity (Critical / High / Medium / Low)
- a category
- a status. **Confirmed** means it was reproduced at runtime or the code path was traced end-to-end. **Suspected** means the mechanism is clear but it wasn't reproduced.

---

## 0. Orientation

### Stack

| Layer | Tech | Entry point |
|---|---|---|
| Backend | Kotlin 2.2, Spring Boot 4.0.3, Spring Security (JWT/jjwt), JPA/Hibernate, Flyway | `StudentStoreFrontApplication.kt` |
| Database | PostgreSQL 15.13. Flyway V1 to V7. Schema validated (`ddl-auto=validate`); triggers enforce sale, review and post invariants | `src/main/resources/db/migration` |
| Async | `@Scheduled` jobs (expiry, reminders, outbox, credential cleanup). Transactional outbox `notification_outbox` | `scheduler/*` |
| WhatsApp | **whatsapp-web.js** (unofficial WhatsApp Web client on puppeteer, `LocalAuth`), **not** the Cloud API | `bot/src/WhatsappBot.js` |
| Bot | Node. An Express webhook on port 3001 for backend→bot calls; JSON-file state; LLM via Gemini or OpenAI-compatible providers with fallback; Langfuse tracing; Cloudinary uploads; ImageMagick downscaling | `bot/src/*` |
| Front-end | Static Alpine.js single-page app served by Spring (`index.html` + `js/app.js`), plus a "layout builder" and theme lab | `src/main/resources/static` |
| External | Cloudinary, SMTP (Gmail defaults), Gemini/OpenAI, Langfuse, DuckDNS for public exposure | |
| Deploy | `scripts/stack.sh` (nohup `java -jar` plus `node`). `docker-compose.yaml` runs Postgres only | |

### Run locally
1. Start the database: `docker compose up -d db`
2. Load `.env`. Spring does not read it itself; `scripts/stack.sh` does.
3. Start the backend: `./gradlew bootRun`
4. Start the bot: `cd bot && node src/WhatsappBot.js`

### Components audited
1. Database
2. Backend (API, auth, services)
3. WhatsApp integration
4. Bot (flow and state)
5. Front-end
6. Async jobs and outbox
7. Config and deploy
8. Tests and CI

Items 6–8 are folded into the backend and cross-cutting sections.

### What was actually run
- **Backend tests:** `./gradlew test` with Testcontainers on rootless podman. Because JDK 21 isn't installed, I used a toolchain override to JDK 24 via an init script outside the repo. Result: **88 tests, 75 passed, 13 failed**. All 13 are stale fixtures; see X-05.
- **Bot tests:** `npm test` (`smoke.js`) passes.
- **App:** started with `bootRun` against a throwaway Postgres 15.13. All 7 migrations applied cleanly to an empty DB.
  - Registered two test users. Verification codes were read from the outbox table, since SMTP is not configured.
  - Created listings and marked one sold, all via the API.
- **Browser:** the built-in browser at 1440×900, 390×844, 844×390, 768×1024 and 1024×768. I took screenshots and ran DOM measurements for overflow, tap targets, modal geometry and error placement.

### What could not be checked
- **The WhatsApp bot was not run live.** It needs a phone-linked WhatsApp account (QR pairing), LLM keys and Cloudinary keys. Everything in sections 3 and 4 comes from code tracing plus the smoke test.
- **Real email delivery.** SMTP credentials are placeholders; delivery attempts failed with "Authentication failed", which is expected.
- **Image upload end-to-end** (needs Cloudinary).
- **iOS Safari / a real on-screen keyboard.** Keyboard-overlap findings are inferred from viewport-height geometry.
- **Screen-reader testing.** Accessibility findings come from DOM inspection only.
- **The production host** (TLS, reverse proxy, firewall) isn't in the repo.

---

## Executive summary: top 10

| # | ID | Sev | Issue |
|---|---|---|---|
| 1 | X-01 | Critical | **Buyers cannot contact sellers anywhere.** The API now nulls `whatsappPhone`/`email` for the public (uncommitted change). Web cards render `mailto:` with no address and an empty WhatsApp `href`. The bot's "reply 1 for a seller link" always fails. |
| 2 | X-05 | High | **The working tree breaks CI.** 13 of 88 backend tests fail. **The edited V2 migration will also stop any existing DB from booting** (Flyway checksum mismatch; see DB-01). |
| 3 | WA-01 | High | **QR login can be hijacked.** The bot accepts `login:<token>` anywhere in any message, with no confirmation step. A crafted wa.me link logs the attacker's browser into the victim's account. |
| 4 | WA-02 | High | **Every message in the target group is stored and sent to a third-party LLM and to Langfuse (keyed by phone number) before consent.** This includes users who already said NO. |
| 5 | BE-02 | High | **"Mark sold" is irreversible.** There is no cancel or decline. A wrong or absent buyer leaves the listing stuck as SOLD: it can't be edited, deleted, renewed or reviewed. |
| 6 | BOT-01 | High | **The bot's price follow-up is a dead end.** It asks you to "reply with just the price (e.g. 15)", then treats "15" as a shopping command, so the draft is never finished. |
| 7 | FE-01 | High | **On phones (≤560px) there is no way to reach your profile.** That means no Confirm Purchase, Mark Sold, reviews, delete listing or delete account. |
| 8 | X-02 | High | **Listings expire after 2 days, and the web neither shows expiry nor offers renewal.** Renewal works only through a WhatsApp reminder sent in the final hour. |
| 9 | BE-03 | High | **Anyone can squat another person's phone number at registration.** Unverified numbers are reserved, which blocks the real owner from signing up. Pending accounts can also be recycled by others. |
| 10 | BE-04 | High | **Deleting an account that has sales leaves its active listings public under "Deleted user".** The UI promises "All your listings will be deleted". |

---

## 1. Database

**DB-01 · High · bug (deploy) · confirmed**: `V2__user_sales_schema_cleanup.sql` was edited after being committed and pushed (commit `5a21118`, 2026-05-14). The diff from that commit is non-empty.
- **Why it matters:** Flyway `validate-on-migrate` (the default) aborts startup on any DB that already recorded the old V2: "Migration checksum mismatch". V3's header ("for installations that already recorded V2") says such DBs exist.
- **Fix:** Restore V2 byte-for-byte to the committed version and move the new statements into V8. If you knowingly accept the edit instead, run `flyway repair` on every environment before deploying.

**DB-02 · Medium · bug · confirmed (runtime)**: Stored timestamps are skewed by the JVM's UTC offset.
- **Where:** `application.properties:20` (`hibernate.jdbc.time_zone=UTC`) combined with entity values from `LocalDateTime.now(ZoneOffset.UTC)`. Hibernate treats a `LocalDateTime` as JVM-local time and converts it to UTC.
- **Observed:** the JVM ran at +01:00. Rows show `created_at = 10:46` while the DB's `now()` was `11:46 UTC`. The API still returned `11:46`, because the conversion reverses on read.
- **Impact:**
  - Round-trips are only consistent while the JVM time zone never changes. Moving the jar into a UTC container, or any host TZ change, shifts every stored timestamp by 1–2h, which affects expiry, archival, token TTLs and rate-limit windows.
  - Values written during the autumn DST overlap hour are ambiguous.
  - DB-side defaults and SQL (`DEFAULT CURRENT_TIMESTAMP` ×12 in V2, `CURRENT_TIMESTAMP` at V2:401) disagree with app-written rows.
- **Fix:** Use `Instant`/`OffsetDateTime` with `timestamptz`. Minimum fix: start the JVM with `-Duser.timezone=UTC` in `stack.sh` and in the tests.

**DB-03 · Medium · security · confirmed (runtime)**: `notification_outbox.payload` stores secrets in plaintext.
- **What:** Email verification codes and password-reset links (`NotificationOutboxService.kt:26`, `:34`). I read a live code (`296383`) straight from the table.
- **Retention:**
  - Delivered rows are purged after 1h (`NotificationOutboxProcessor.kt:53`).
  - **Undelivered rows keep live secrets indefinitely.** In the test, SMTP failure meant every code stayed.
- **Fix:** Store a reference (user id / token id) and render the secret at send time, or encrypt the payload. Delete undelivered credential rows once the token TTL passes.

**DB-04 · Medium · bug · confirmed**: The outbox retries forever.
- **What:** `attempts += 1` with back-off capped at 1h (`NotificationOutboxService.kt:95`, `:114`), but no maximum attempts and no per-type TTL.
- **Effect:**
  - Expired verification codes, reminders for already-archived posts and review requests still get sent hours or days later once the channel recovers.
  - Permanently failing rows are retried forever.
- **Fix:**
  - Add `max_attempts` and a dead-letter status.
  - Set a per-type `expires_at`.
  - Skip reminders whose post is no longer ACTIVE.

**DB-05 · Low · performance · suspected**: `Review` and `Sale` use the default EAGER `@ManyToOne`/`@OneToOne`. Expect N+1 queries in review lists and `findPendingConfirmationsForBuyer`. **Fix:** LAZY plus fetch joins or entity graphs.

**DB-06 · Low · bug · suspected**: `PostRepository.markReminderSentIfPending` updates `reminder_sent_at` without bumping `version`. A concurrent `copy()`-and-save of a stale `Post` (edit or renew) can overwrite it back to null and trigger a duplicate reminder; the outbox `dedupe_key` only partly mitigates this. **Fix:** bump `version` in the update.

**DB-07 · Low · scalability · confirmed (code)**: `NotificationOutboxRepository.findDueForUpdate` uses `PESSIMISTIC_WRITE` without `SKIP LOCKED`. A second app instance would block rather than share the work. That's fine for one instance; fix it before scaling out.

**DB-08 · Low · performance**: `searchAvailablePosts` uses `LIKE '%q%'` on title and description with no trigram index. That's fine at campus scale; add `pg_trgm` GIN indexes if search slows down.

**DB-09 · Low · ops · suspected**: `docker-compose.yaml` mounts the named volume at `/var/lib/postgresql`, but the image's `VOLUME`/PGDATA is `/var/lib/postgresql/data`. So the data lives in an anonymous volume, and a `down`/`up` cycle can come back with an empty database. **Fix:** mount at `/var/lib/postgresql/data`.

**DB-10 · Low · edge case · suspected**: V7 `uk_users_pending_email` makes `lower(pending_email)` unique among pending emails, but nothing stops a pending email from equalling another user's active `email`. The check only happens at confirm time, so the user finds out after entering the code. **Fix:** check against `users.email` when the change is requested.

---

## 2. Backend

**BE-01 · Critical · bug · confirmed (runtime)**: Public post responses return `email: null, whatsappPhone: null`.
- **Where:** `PostService.kt:534–557`, where `canViewContact = canViewBuyer` (owner, buyer or admin only).
- **History:** this is an uncommitted change that reverses commit `1726e6d` ("Fix WhatsApp contact action on listings"). `GET /api/posts/1` as anonymous, and as a logged-in non-owner, returned null for both.
- **Effect:** it removes the marketplace's only buyer→seller channel (see X-01). `PostSearchControllerTest` "search responses include seller phone number" still asserts the old behaviour.
- **Fix:** Pick a policy deliberately. One option is to expose contact details (or a server-built `wa.me` link) to authenticated users; another is a "request contact" endpoint. Then update the front-end and the test together.

**BE-02 · High · bug/UX · confirmed (runtime)**: Marking a listing sold can't be undone.
- **Flow:** `markAsSold` (`PostService.kt:191–227`) sets the post to SOLD immediately and creates a Sale with `buyer_confirmed_at = null`.
- **Missing:** there is no endpoint to cancel the sale or to decline as the buyer (grep shows only `confirmPurchase` and `getPendingConfirmations` in `SaleService`).
- **Runtime check:** after a mark-sold,
  - `DELETE /api/posts/2` returned 400
  - `PUT /api/posts/2` returned 400
  - re-marking returned 400 "Listing is already marked as sold"
  - `renewPost` rejects sold posts (`PostService.kt:238`)
- **Effect:** choosing the wrong buyer, which is easy because buyers are picked by name only (FE-12), or a buyer who never confirms, leaves the listing stuck. Neither side can leave a review.
- **Fix:**
  - Let the seller undo while `buyer_confirmed_at IS NULL`.
  - Let the buyer decline ("this wasn't me").
  - Auto-revert unconfirmed sales after N days. The triggers already allow deleting the Sale row.

**BE-03 · High · security · confirmed (code)**: Phone squatting and pending-account takeover at registration (`UserService.resolveRegistrationUser`, `UserService.kt:264–286`).
- **Phone squatting:**
  - Phone uniqueness is enforced for **unverified** numbers (line 272).
  - An attacker registers with their own email and the victim's phone, then verifies the email.
  - The victim's registration then fails, and the attacker's account is never challenged to prove it owns that phone.
- **Pending-account takeover:** anyone registering with the same email *or* phone as a pending account can recycle or merge it, which deletes the other pending account.
- **Fix:**
  - Reserve a phone only once `phone_verified_at` is set (a partial unique index).
  - Verify the phone through the bot before binding it.
  - Never delete another identity's pending row because a different identifier matches.

**BE-04 · High · bug/privacy · confirmed (code)**: Account deletion leaves listings public when the user has sales.
- **Where:** `deleteOwnAccount` → `anonymizeUser` (`UserService.kt:394–409`).
- **What happens:** it renames the user and scrubs their PII, but **does not archive or delete their non-sold posts or media**. Those listings stay publicly listed under "Deleted user", with no contact path.
- **UI contradiction:** the delete modal says "This action is permanent. All your listings will be deleted." (`index.html:1213`).
- **Also:** the password is set to a raw UUID rather than a hash. That's harmless, since it can never match a bcrypt comparison, but it's odd.
- **Fix:** archive all non-SOLD posts and delete their Cloudinary media inside `anonymizeUser`.

**BE-05 · Medium · UX/maintainability · confirmed (runtime)**: Error handling loses information and uses inconsistent shapes.
- **Message whitelist:**
  - `GlobalExceptionHandler.kt:105–111` passes through `IllegalArgumentException` messages only if they appear in the `SAFE_MESSAGES` list; everything else becomes "Invalid request".
  - Several legitimate service messages aren't on the list (for example "Cover index is out of range"), so users see "Invalid request".
- **Wrong status for not-found:** it's thrown as `IllegalArgumentException`, giving `400 {"code":"INVALID_REQUEST","message":"Post not found"}` (observed on `PATCH /api/posts/999/mark-sold`) instead of 404.
- **Inconsistent envelope:** the security entry point and filters emit `{message}`, while the controller advice emits `{code, message}`.
- **Fix:**
  - Add typed exceptions (`NotFound`→404, `Conflict`→409, `DomainValidation`→400 with a user-safe message).
  - Use one error envelope everywhere.

**BE-06 · Medium · security/ops · confirmed (code + config)**: Unsafe defaults when secrets are missing.
- **JWT key:** a blank `JWT_SECRET` falls back to a random per-process key (`JwtService.kt:22`, `:107`). Every restart logs everyone out, and two instances reject each other's tokens.
- **Secret checks:** `SecretsConfiguration` only enforces secrets under the `prod` profile or `REQUIRE_SECURE_SECRETS=true`. But `.env.example` documents a public DuckDNS deployment with `SPRING_PROFILES_ACTIVE=local`. That leaves secret checks off and `spring.jpa.show-sql=true`, so every SQL statement is logged.
- **Fix:**
  - Run the public deployment with `prod`.
  - Fail fast on a blank or weak `JWT_SECRET` unless the profile is `test`.

**BE-07 · Medium · security · confirmed (code + runtime)**: Rate limiting is easy to bypass and too coarse (`RateLimitFilter.kt`).
- **Spoofable IP:** behind a trusted proxy it uses the **leftmost** `X-Forwarded-For` entry (lines 120–129). That entry is client-controlled whenever the proxy appends rather than overwrites, so a client can mint new IPs and bypass the limit.
- **Shared bucket:** without `TRUSTED_PROXY_ADDRESSES` behind a proxy, all users share the proxy's bucket. Registration is 3/min per IP; I hit the limit after 3 calls.
- **Coverage gaps:** limits apply per IP only, with no per-account login lockout. There are no limits on post creation, uploads, reviews, favourites or the bot endpoints.
- **Fix:**
  - Take the right-most untrusted hop, or use Spring's `ForwardedHeaderFilter` with a configured proxy.
  - Add a per-account login back-off and per-user write limits.

**BE-08 · Medium · performance/reliability · confirmed (code)**: Scheduler jobs can block each other.
- **Why:** all `@Scheduled` jobs share Spring's default single-thread scheduler. Outbox delivery calls the bot with `.block(Duration.ofSeconds(10))` (`BotNotificationService.kt:21`, `:31`, `:41`), one message at a time.
- **Effect:** while the bot is down, every outbox tick blocks the thread for 10s × batch size, delaying archival and reminder jobs. With DB-04, this repeats forever.
- **Duplicate work:** `CredentialCleanupScheduler` duplicates `WhatsAppQrLoginService.expireStaleSessions`; both run every 5 minutes.
- **Fix:**
  - Set `spring.task.scheduling.pool.size`.
  - Add a circuit breaker or skip-ahead when the bot is unreachable.
  - Drop the duplicate job.

**BE-09 · Low · security · confirmed (code)**: Account enumeration. Register replies "Email already exists", and resend-verification replies "No account found". **Fix:** use a generic response.

**BE-10 · Low · security · confirmed (code)**: `MediaUrlPolicy` accepts any HTTPS host for listing images, so a listing can embed attacker-hosted tracking pixels. It also does a blocking DNS lookup in the request thread, and the check is TOCTOU-prone. **Fix:** only allow your Cloudinary delivery host.

**BE-11 · Low · bug · confirmed (code)**: `CloudinaryService` swallows upload errors (returns null), so the cause is lost. It also trusts the client-supplied content type. **Fix:** surface the error and sniff magic bytes.

**BE-12 · Low · security · confirmed (code)**: Password-reset links carry the token in the query string (`PasswordResetService.kt:40`), so it lands in access logs, browser history and the `Referer` header. **Fix:** put it in the fragment (`#reset-password?token=`).

**BE-13 · Low · security**: `@CrossOrigin` allows `https://*.duckdns.org` (anyone can register one) and `http://free2:*`. Bearer-token auth limits the impact. **Fix:** allow the exact origin only.

**BE-14 · Low · bug · suspected**: `createPostAsBot` doesn't check `user.isEnabled`. Whether a pending or disabled account can post through the bot depends on what `/api/users/by-phone` returns for such users. **Fix:** check `isEnabled` explicitly.

**BE-15 · Low · performance**: Each request does 2 DB queries in `JwtAuthenticationFilter`, plus repeated `findByEmail` lookups inside services. Fine at current scale.

---

## 3. WhatsApp integration (whatsapp-web.js)

Several Cloud-API concerns don't apply here: webhook signature verification, the 24h customer-service window and template messages. This client drives a normal WhatsApp account. The equivalent risks are listed below.

**WA-01 · High · security · confirmed (code)**: QR login is hijackable (QRLjacking).
- **Where:** `handleLoginCommand` matches `/login:([0-9a-f-]{36})/i` **anywhere** in a DM (`bot/src/WhatsappBot.js:249`, unanchored), then calls `confirmLogin`, which immediately authorises the waiting browser session.
- **Attack:**
  1. The attacker opens the login page, which creates a session and a token.
  2. The attacker sends the victim a link: `https://wa.me/<bot>?text=Hi! login:<token>`.
  3. The victim taps Send.
  4. The attacker's browser receives the victim's JWT.
- **Fix:**
  - Anchor the regex (`^login:<uuid>$`).
  - Have the bot reply "Log in on a new browser (city/device, started HH:MM)? Reply YES within 2 min", and confirm only on YES.
  - Optionally show a short code in the browser that the user must type back.

**WA-02 · High · privacy · confirmed (code)**: Group messages go to third parties before consent.
- **What's collected:** for every target-group message, `handleTargetGroupMessage` (`WhatsappBot.js:234–245`) does two things:
  - It stores the text and **downloads the media**, persisting base64 images to `userStatePersistence.json`.
  - After 5 minutes of inactivity, `scheduleGroupClassification` (`:165–231`) sends all of it to the LLM provider, with a Langfuse trace whose `userId` is the phone number.
- **Ordering:** consent is only asked afterwards (`:209–217`). The "declined" check runs after the LLM call (`:198` vs `:180`).
- **Declines are forgotten:** the decline list `NOT_CONSENTED_TO_MESSAGE_UPLOAD` is in memory only (`:29`), so after a restart, people who said NO get asked again.
- **Fix:**
  - Classify only users who have consented, or run a cheap local pre-filter first.
  - Don't download media before consent.
  - Persist declines.
  - Hash phone numbers in traces.

**WA-03 · Medium · privacy · confirmed**: `bot/consentedUsersPersistence.json` is **tracked in git** despite the `.gitignore` entry (commits `2b09344`, `f7464fb`, `891cef3`), and it contains a real phone number. **Fix:** `git rm --cached` the file; consider whether the history needs scrubbing.

**WA-04 · Medium · edge case · suspected**: LID contacts. `chatIdentity.js` treats `@lid` chats as direct chats, but `getContactNumber` falls back to `contact.id.user`, which for an `@lid` contact is a LID, not a phone number. Users with LID addressing can't log in, can't be matched to an account, and get "not registered". Verify with a LID account on a real device.

**WA-05 · Medium · reliability · confirmed (code)**: Restarts lose dedupe and timer state.
- **Dedupe:** `processedMessageIds` is held in memory (2,000 IDs). Messages re-delivered after a reconnect or restart get processed twice.
- **Timers:** the per-user inactivity `setTimeout`s aren't persisted. Drafts restored from disk (`BotStateStore.load`) never fire, and sit until the user writes again or the 24h cleanup deletes them.
- **Fix:** on startup, reschedule timers for restored drafts and persist recent message IDs.

**WA-06 · Medium · security · confirmed (code)**: The bot's webhook server is too open.
- `webhookService.js` calls `app.listen(port)` with no host, so it listens on all interfaces over plain HTTP with no rate limit.
- `/send-message` sends arbitrary text to any number, protected only by the shared `X-Bot-Api-Key`.
- **Fix:** bind to `127.0.0.1`, or sign each request with an HMAC and a timestamp.

**WA-07 · Medium · bug · confirmed (code)**: No delivery truth. `/webhook/user-registered` returns 200 *before* sending the WhatsApp message, so the backend marks the outbox row as sent even if the send fails. There is no ack or delivery-status tracking anywhere. **Fix:** respond only after `sendMessage` resolves (or report failure), and record WhatsApp ack events.

**WA-08 · Medium · ops risk**: The whole login and bot channel depends on an unofficial client. A WhatsApp ban or a Web protocol change takes down login-by-QR, listing intake and reminders together. There is no outage handling: no health signal, and no web banner when the bot is down. **Fix:** expose bot health, show a web notice when it's down, and keep email login as the primary path.

**WA-09 · Low · config · confirmed (code)**: Hard-coded production defaults: `TARGET_GROUP` JID (`WhatsappBot.js:45`), `appBaseUrl` (`:96`), and the register URL (`:264`). A dev instance started without `.env` reads the real group and links to production. **Fix:** require these as environment variables.

**Checked and OK:**
- The webhook API key is compared with `timingSafeEqual`.
- Phone normalisation between the bot (digits only) and the backend (`+digits`) is consistent for renewals and lookups (`PostService.kt:232`).
- `safeRemoteMedia.js` (SSRF guard, size and time limits, pinned DNS) is solid.

---

## 4. Bot (flow and state)

**BOT-01 · High · bug · confirmed (code trace)**: The price follow-up is a dead end.
- **What the bot says:** after parsing a listing without a price, it says "Reply with just the price (for example: "15")" (`ListingSubmissionService.js:360`) and sets mode `awaiting-price`.
- **Why it breaks:**
  - In `handleDmPostFlow`, `if (shoppingChat.isKnownCommandText(msg.body)) return false` (`WhatsappBot.js:295`) runs first.
  - A bare number is a shopping "select" command (`WhatsAppShoppingChat.js:143`).
  - So "15" goes to the shopping handler, which replies 'Send "shop" to start browsing listings.' If a shopping session exists, it instead sends a seller link for item 15, or for **post #15** via `getPostById` (`:385`).
  - The draft is never completed and expires after 24h.
- **Related:** in `dm-post` mode, any shopping-command text (e.g. a message that is just "20", or "n") **wipes the draft** (`WhatsappBot.js:349–351`).
- **Fix:** while in `awaiting-price` or `dm-post`, parse numbers and `1=20, 2=15` patterns as prices before checking shopping commands.

**BOT-02 · High · bug · confirmed (code + runtime DTO)**: The seller link can never be built.
- **Where:** `buildSellerWhatsAppLink` reads `item.user.phoneNumber` (`WhatsAppShoppingChat.js:393`).
- **Why it fails:** post responses carry `user: {userId, name}` (`PublicUserResponseDTO`), and the bot calls `/api/posts/*` without credentials.
- **Effect:** every "1" / "contact 1" reply gets "This user does not have a usable WhatsApp number." This is independent of BE-01.
- **Fix:** add a bot-key-protected endpoint that returns the contact link for a post.

**BOT-03 · Medium · bug · confirmed (code)**: An LLM outage destroys the user's draft.
- **Parser:** `MessageParser` returns `null` when every provider fails (`modelRouter.js:75–78`).
  - `normalizeParsedListings(null)` then returns `invalid-listing`. That reason isn't handled in `submitListingDraft`, so it falls through to the generic branch, which **clears the state** (`ListingSubmissionService.js:451–453`).
  - All of the user's text and photos are lost, though the message says "try again later".
- **Classifier:** `ContextClassifier` returns "NO" on outage (`modelRouter.js:95`), so group listings are silently discarded (`WhatsappBot.js:189–193`).
- **Fix:** treat a parser/classifier failure as retryable, keep the draft and retry on a timer.

**BOT-04 · Medium · bug · suspected**: Retries can create duplicate listings.
- **Why:** the idempotency key hashes the LLM's output (`title:price:description`, `ListingSubmissionService.js:167–169`). After a `partial-listing-conflict` or `retryable-listing-failure`, the draft is kept, and the retry runs the LLM parse again.
- **Effect:** any wording difference, which is likely with multi-item photos, yields new keys. Items that were already created get posted again.
- **Fix:** derive the key from the draft (message IDs plus image hashes plus item index), or record which items were already posted.

**BOT-05 · Medium · edge case/performance · confirmed (code)**: Media handling is unbounded.
- **No type filter or null check:** `downloadListingImage` accepts any media (video, PDF, voice, sticker) and doesn't null-check `downloadMedia()`, which returns `undefined` for expired media, causing a TypeError and no reply (`WhatsappBot.js:127–134`).
  - Non-images then fail the Cloudinary upload, which is reported as "retryable", indefinitely.
- **No count cap:** the backend allows 5 images; the bot accepts any number.
- **State bloat:** base64 media lives in the JSON state file, which is rewritten in full with `writeFileSync` on every message (`BotStateStore.js:64–86`). That blocks the event loop as drafts grow.
- **Fix:** accept `image/*` only, cap at 5 and by size, and store media files by hash with only references in state.

**BOT-06 · Medium · UX · confirmed (code)**: Silent dead ends and a rigid consent prompt.
- **No fallback:** an unrecognised DM ("hi", "how do I sell?") gets no reply; `handleDirectMessage` falls through (`WhatsappBot.js:343–353`).
- **Errors are silent:** exceptions inside handlers are only logged.
- **Rigid consent:** only exact `yes`/`no` work (`:322`, `:333`). "Yes!", "ja" or "👍" are ignored with no re-prompt.
- **No human handoff:** there is no path to a person, and no "help" hint while a draft is pending.
- **Fix:**
  - Add a default reply that shows the help menu.
  - Normalise yes/no variants and re-prompt.
  - Add a catch-all "Something went wrong, your draft is safe" reply.

**BOT-07 · Medium · UX · confirmed (code)**: The bot hijacks group chatter. `isKnownBotCommand` treats bare numbers, `yes`/`no`, `n`, `p` and `stop` as commands in the target group (`WhatsappBot.js:117–125`). A member replying "2" or "yes" to a human triggers a DM *and* a group message ("I sent the shopping response to your DM…"). **Fix:** in the group, respond only to prefixed commands (`/shop`, `/search …`).

**BOT-08 · Low · bug · confirmed (code)**: `normalizePrice("1,200")` gives `1.2` (`ListingSubmissionService.js:27`). This only matters when the LLM returns a string, but European thousands separators are common.

**BOT-09 · Low · concurrency · suspected**: There's no per-user lock. A double "yes", or the timer firing while a reply is processed, can run `submitListingDraft` twice in parallel for the same phone. That's mitigated only if both LLM parses happen to be identical (BOT-04).

**BOT-10 · Low · reliability**: The LLM providers set no request timeout (neither provider file uses `timeout` or `AbortController`), so one hung call stalls that user's flow indefinitely. The draft TTL is "24h since the *last* message" and is enforced by a 3-hourly sweep (`WhatsappBot.js:389–391`), so it really lasts up to 27h.

**BOT-11 · Low · ops**: The ImageMagick `magick` binary is an undeclared runtime dependency (`imagePreprocessor.js`). If it's missing, the bot silently sends full-size images to the LLM, which costs more and is slower.

---

## 5. Front-end

Viewports tested: 1440×900, 390×844, 844×390, 768×1024 and 1024×768. Tap targets are measured against 44×44px.

**FE-01 · High · UX · confirmed (390×844)**: There's no route to the profile on phones.
- **Cause:** `css/shell.css:511–513` hides `.shell-user-actions .user-link:first-child` (the "Hi, name" link) at ≤560px, and no other navigation item leads to the profile. The sidebar has Marketplace, Saved, Layout Builder and Support.
- **Effect:** on a phone you can't confirm a purchase, mark an item sold, leave reviews, delete a listing or delete your account.
- **Fix:** add a "Profile" entry to the sidebar or bottom nav, or keep an avatar button.

**FE-02 · Medium · UI · confirmed (390×844, 768×1024)**: Header actions overflow.
- At 390px, "Log Out" sits at x = 386–459 and "Create Listing" is clipped.
- At 768px, "Log Out" is clipped.
- They're reachable only by horizontally scrolling `.shell-actions-list` (`overflow:auto`, `max-width:min(52vw,14rem)`), which shows no affordance.
- **Fix:** collapse into an overflow menu below ~600px.

**FE-03 · Medium · UI · confirmed (1440×900)**: The listing detail page breaks on long words.
- **Observed:** a title or description with a long unbroken token pushes the info panel to x = 2245 in a 1425px viewport. The title, price, Save button and description are cut off.
- **Cause:** the grid `minmax(17rem, 0.42fr)` in `css/layout-system.css:125–131` lacks `min-width:0` and `overflow-wrap`.
- **Fix:** `.listing-detail-panel{min-width:0}` and `.listing-detail-panel h1, .listing-detail-description{overflow-wrap:anywhere}`. Cards already truncate.

**FE-04 · Medium · bug · confirmed (runtime)**: Contact actions are empty.
- Every card shows an "Email user" button with `href="mailto:"` and no address: `index.html:346` has no `x-show` guard.
- The WhatsApp button has `href=""` but is hidden.
- The detail view shows **no** contact action for non-owners (`index.html:415–420`).
- These are the front-end side of BE-01.
- **Fix:** guard the email button with `x-show`, and show a clear "Log in to contact" or "Contact unavailable" state.

**FE-05 · Medium · UX/accessibility · confirmed (390×844)**: Form errors are invisible on mobile.
- **Placement:** submitting "Create listing" with a 5-character description renders the error in the global banner at the top of the page (`index.html:145–148`). While the user is at the submit button, that banner is at y = −547px.
- **No signal:** there's no scroll, no focus move, and no `role="alert"` or `aria-live`.
- **Raw text:** the message reads "description: Description must be between 10 and 1000 characters Code: VALIDATION_FAILED". The field key and error code are added in `js/core/api-client.js:24–30`.
- **No client check:** there's no client-side `minlength` (`index.html:657`).
- **Fix:**
  - Show field-level errors inline, scroll to or focus the first error, and add `role="alert"`.
  - Drop the `Code:` suffix from user-facing text.
  - Mirror the backend limits in the markup.

**FE-06 · Medium · UI/accessibility · confirmed (844×390)**: Modals don't fit short screens and aren't real dialogs.
- **Fit:** above 768px wide, `.modal-overlay` centres content with no overflow (`css/profile.css:190–200`); the scrollable variant only applies ≤768px (`:276–280`).
  - At 844×390, the smallest modal (Delete Account) already spans y = −2 to 392.
  - Mark Sold (with buyer results) and Review (with a textarea) are taller and get clipped with no way to scroll. An on-screen keyboard makes it worse.
- **Dialog semantics:** modals lack `role="dialog"`, `aria-modal`, a focus move or trap, and Escape-to-close. Focus stayed on the trigger button.
- **Fix:**
  - Use `overflow:auto` and `align-items:flex-start` whenever `max-height` is under ~600px.
  - Use `max-height: 100dvh`.
  - Use `<dialog>`, or add the ARIA attributes, focus management and Escape handling.

**FE-07 · Medium · UX · confirmed (844×390, 768×1024)**: Chrome takes too much of the screen.
- The sticky header is 159px tall, which is 41% of a 390px landscape screen.
- The "Sites" builder block comes before the content, so the first listing starts at y = 514, below the fold, in landscape.
- **Fix:** don't make the header sticky at `max-height:500px`; move or collapse the builder/sites panel, which is a dev feature, away from shoppers.

**FE-08 · Medium · UX · confirmed (code)**: There's no profile-editing UI. `js/services/user-service.js` has no update call, and no form exists. Users can't change their name, phone, email or password from the web. The backend's email-change flow (pending email plus code) and phone/password changes (`PUT /api/users/{id}`) can't be reached. **Fix:** add an "Edit profile" form. Remember that a phone or password change bumps `tokenVersion`, so the UI must log the user out or re-authenticate them afterwards.

**FE-09 · Low · bug · confirmed (runtime console)**: An uncaught `TypeError: Cannot read properties of null (reading 'regions')` fires on page loads.
- **Cause:** `index.html:942` uses `builder.draftLayout.regions` without optional chaining while `draftLayout` is null.
- **Related, suspected:** once, the Delete Account modal stayed visible after Cancel even though its state was already `false`. An Alpine transition interrupted by this error is the likely cause.
- **Fix:** `builder.draftLayout?.regions || []`.

**FE-10 · Low · accessibility · confirmed**: Tap targets under 44px:
- "Create site" (30–37px)
- the card email and favourite buttons (38–42px)
- sidebar nav items (36–43px tall)
- mobile header buttons (34–36px tall)
- the "Forgot your password?" and "Sign up" links (23px tall)

**FE-11 · Low · UI · confirmed**: `formatPrice` gives `$999999.99`: no grouping and a hard-coded `$` (`js/app.js:1459`). The create form's label is "Price ($)" (`index.html:624`), while the bot says "EUR" (X-07). **Fix:** `Intl.NumberFormat('de-DE', {style:'currency', currency:'EUR'})`.

**FE-12 · Low · UX · confirmed (code)**: The Mark Sold modal identifies buyers by name only (`index.html:1172–1176`). Two people with the same name are indistinguishable, and with BE-02 a wrong pick is permanent. **Fix:** show a disambiguator (masked email or phone, or avatar) and a confirmation step.

**FE-13 · Low · UI · confirmed (runtime)**: Your own profile briefly shows the placeholder `user@constructor.university` before the real data loads. **Fix:** show a skeleton instead of fake values.

**FE-14 · Low · security · confirmed (code)**: The JWT is stored in `localStorage` (`js/stores/auth-store.js:22`), so any XSS leads to account takeover. The XSS surface is small: rendering uses `x-text` and no `x-html`/`innerHTML` was found. Adding a CSP header would contain it. No CSP header was observed; this was not checked exhaustively.

**Checked and OK:**
- Form inputs use 16px text, so iOS won't zoom on focus.
- There's no horizontal page scroll at any tested size (the overflow is clipped inside containers instead; see FE-02/03).
- Labels are associated with the create-listing inputs.
- The 401 handler clears the session.

---

## 6. Cross-cutting

**X-01 · Critical · bug · confirmed**: The buyer→seller journey is broken end-to-end.
- **Trace:**
  1. A buyer browses on the web.
  2. The card shows an empty `mailto:` (FE-04).
  3. The detail page shows no contact action (FE-04).
  4. The API withholds the seller's contact details (BE-01).
  5. In the bot, "1" always fails (BOT-02).
- **Effect:** a marketplace whose only purpose is putting buyers in touch with sellers currently can't. The only remaining path is guessing the seller from the group chat.
- **Fix:** decide the contact policy (BE-01) and implement it across the API, the cards, the detail view and the bot in one change.

**X-02 · High · UX/bug · confirmed**: The listing lifecycle doesn't match across layers.
- **Expiry:** listings expire 2 days after creation or renewal (`PostService.kt:249`, `:289`).
- **Reminder:** the only reminder is a WhatsApp message, enqueued when under 1h remains (`PostExpirationScheduler.kt:24`). Its text prints the raw ISO timestamp with no zone, e.g. "expires at 2026-11-21T11:46:00.39", which is UTC but unlabelled (`NotificationOutboxService.kt:74`).
- **Renewal:** it works only via the bot (`renew <id>`). `renewPost` already supports a web owner (`PostService.kt:235`), but no web endpoint or UI exists. The web never shows `expiresAt` or the ARCHIVED status.
- **Late reminders:** with outbox back-off (up to 1h, DB-04), the reminder can arrive after the listing is archived.
- **Effect:** web-only sellers silently lose their listings every 48h.
- **Fix:**
  - Show the expiry and status on profile listings.
  - Add `POST /api/posts/{id}/renew` (JWT) and a Renew button.
  - Send the reminder earlier (e.g. 12h before) and format it in local time.
  - Consider a longer default lifetime.

**X-03 · Medium · contract · confirmed (runtime)**: Timestamps have no time-zone offset in the API.
- The API serialises `LocalDateTime` with no offset (`"createdAt":"2026-11-19T11:46:00.390968"`), although the value is UTC.
- The front-end runs `new Date(...)`, which parses it as *local* time (`index.html:411`, `:544`, `:575`). In Germany every displayed time is 1–2h early, so listings and sales created shortly after local midnight show the previous day.
- DB-02 adds a storage-level skew on top.
- **Fix:** emit an `Instant`/ISO string with `Z`, or append `Z` in a Jackson serializer, and format with `Intl.DateTimeFormat`.

**X-04 · Medium · contract · confirmed**: The error contract is inconsistent.
- The backend uses three shapes: `{code,message}` from the advice, `{message}` from the security layer, and plain-text 400s.
- The front-end merges `message`, `error`, `errors[]`, `detail` and `code` into one string (`api-client.js:12–38`).
- See BE-05 and FE-05.
- **Fix:** one envelope, `{code, message, fieldErrors:{field:msg}}`, rendered per field.

**X-05 · High · test/CI · confirmed (runtime)**: The working tree turns CI red, and coverage has gaps.
- **Failures:** `./gradlew test` gives 88 tests, **13 failing**, all stale fixtures that violate the new invariants:
  - `PostSearchControllerTest` (4): its setup inserts `isSold=true` with status ACTIVE, which violates `chk_posts_sale_state`. It also asserts the old public `whatsappPhone`.
  - `ReviewWorkflowControllerTest` (5): `saveSoldSale` (lines 207–225) leaves the post ACTIVE.
  - `PostExpirationControllerTest` (4): the renew tests omit `X-Bot-Phone` and get a 403.
- **CI:** `.github/workflows/ci.yml` runs these tests, so committing the tree as-is fails CI. It also starts a Postgres service that Testcontainers doesn't use.
- **Untested risky paths:**
  - buyer confirmation (`SaleService`)
  - outbox processing and retries
  - account deletion of a user with sales (BE-04)
  - `EmailVerificationService` and email change
  - QR-login confirmation
  - the bot's conversation state machine. The smoke test covers only the retry paths, which is why BOT-01 and BOT-03 slipped through.
- **Fix:** fix the fixtures (create sales through `markAsSold`, pass `X-Bot-Phone`), then add targeted tests for the paths above.

**X-06 · Medium · deploy/ops · confirmed (config) / suspected (TLS)**: Deployment and operations are fragile.
- **Process management:** `scripts/stack.sh` launches the app and the bot with `nohup setsid` (lines 81, 96). Nothing restarts them on crash or reboot.
- **Logs:** they are **truncated on every start** (`: >"$SPRING_LOG"` at line 76, `>` at line 100), with no rotation.
- **Health checks:** none (no actuator).
- **TLS:** `.env.example` binds 8080 on `0.0.0.0` "through DuckDNS/router forwarding", and there's no TLS configuration in the repo. Unless an external proxy terminates HTTPS, passwords and JWTs travel in cleartext. **This is suspected; I couldn't see the host.**
- **Single instance only:** the design assumes one instance (in-memory Caffeine buckets, the bot's JSON state, `@Scheduled` with no distributed lock, DB-07).
- **Fix:**
  - Use systemd units (or compose services) with restart policies.
  - Append logs and rotate them.
  - Add `/actuator/health` for both services.
  - Put Caddy or nginx with TLS in front and bind the app to localhost.
  - Use the `prod` profile (BE-06).

**X-07 · Low · consistency · confirmed**: Currency differs by channel: `$` on the web (`formatPrice`, "Price ($)") versus `EUR` in the bot (`WhatsAppShoppingChat.js:457`). The user base is in Bremen.

**X-08 · Low · maintainability · confirmed**: Duplicated logic. The category list is defined in four places: the backend enum, `ListingSubmissionService.js:4`, `WhatsAppShoppingChat.js:6` and the front-end select. Phone normalisation is in three places: `IdentityNormalizer`, the bot's `normalizePhoneNumber` and the front-end validator. They can drift. The front-end contract check (`scripts/validate-frontend-contracts.js`) could be extended to cover categories.

**X-09 · Low · observability · confirmed (code)**:
- **No correlation ID** links a backend request, its outbox row and the bot's handling.
- **Outbox failures are only visible** in the `last_error` column.
- **Langfuse traces use raw phone numbers** as `userId`/`sessionId` (WA-02).
- **Fix:** propagate an `X-Request-Id`, add a metric or alert on outbox rows that are past due or have more than N attempts, and hash identifiers in traces.

---

## 7. Fix plan

### Quick wins (each ≤ ½ day, low risk)
1. **Restore V2** to its committed content and move the edits to a V8 migration (DB-01).
2. **Fix the 13 stale tests** so CI is green again (X-05).
3. **Decide the contact policy** and make it consistent: revert or adapt BE-01, guard the email button (FE-04), and add a bot contact endpoint (BOT-02). Together these resolve X-01.
4. **Bot hardening:**
   - Anchor the login regex and add a YES confirmation (WA-01).
   - Parse numbers as prices before shopping commands (BOT-01).
   - Treat a null parse as retryable (BOT-03).
   - Accept `image/*` only and null-check downloads (BOT-05).
5. **Show the profile link on mobile** (FE-01) and put the header actions in an overflow menu (FE-02).
6. **CSS fixes:**
   - `min-width:0` and `overflow-wrap` on the detail panel (FE-03).
   - A scrollable modal overlay on short viewports (FE-06).
   - Optional chaining at `index.html:942` (FE-09).
7. **Make errors visible:** inline or scroll-to-error with `role="alert"`, and drop the "Code:" suffix (FE-05).
8. **Untrack** `bot/consentedUsersPersistence.json` (WA-03).
9. **Run with `-Duser.timezone=UTC`** and the `prod` profile in deployment (DB-02, BE-06).
10. **Bind the bot webhook** to localhost (WA-06).
11. **Add a max-attempts cap and TTL** to the outbox, and stop storing secrets in the payload (DB-03, DB-04).
12. **Format prices in EUR** (FE-11, X-07).

### Medium (1 to 3 days each)
13. **Sale lifecycle:** seller undo, buyer decline, auto-revert of unconfirmed sales, and a buyer disambiguator (BE-02, FE-12).
14. **Web renewal:** a JWT renew endpoint, expiry/status shown on the profile, and an earlier, localised reminder (X-02).
15. **Registration identity rules:** reserve phones only once verified, and verify phones via the bot; stop recycling pending accounts (BE-03).
16. **Account deletion:** archive listings and media for anonymised users, and align the UI copy (BE-04).
17. **Consent-first bot intake:** no LLM call or media download before consent; persisted declines; hashed trace IDs (WA-02).
18. **Typed exceptions and one error envelope** with field errors (BE-05, X-04).
19. **Edit-profile UI**, including the token-version logout (FE-08).
20. **Rate limiting:** correct XFF handling plus per-account and per-endpoint limits (BE-07).

### Larger refactors
21. **Time model:** move to `Instant` / `timestamptz` everywhere and use offset-aware JSON (DB-02, X-03).
22. **Bot state:** move from the JSON file with base64 media to SQLite/Postgres plus media on disk. Persist timers and dedupe; add a per-user mutex (BOT-05, WA-05, BOT-09).
23. **Ops:**
    - systemd or compose for the app and bot, with restarts.
    - A TLS reverse proxy.
    - Health endpoints and log rotation.
    - A scheduler thread pool and a circuit breaker for bot calls (X-06, BE-08).
24. **Test coverage:**
    - Sale confirmation, the outbox, deletion and QR login on the backend.
    - A table-driven conversation test harness for the bot's state machine, with a fake `client` and LLM.
25. **Long-term:** consider the official WhatsApp Cloud API to remove the ban and protocol risk (WA-08). It brings webhook signatures, templates and the 24h window into scope.

## 8. Fix status (audit-fixes branch, 2026-11-21)

Decisions taken: contact details are shown to logged-in users; only the old V2 migration is restored (later migrations stay); the server runs on Europe/Berlin while the database keeps UTC wall-clock values.

### Fixed
- **Database:**
  - DB-01: V2 restored, with the edits moved to a later migration.
  - DB-02: UTC storage via a `Clock` bean, and the server time zone is set explicitly.
  - DB-03 and DB-04: no secrets in outbox payloads; max attempts, TTL and dead-lettering (V9).
  - DB-06: reminder bump increments `version`.
  - DB-07: `SKIP LOCKED` on the outbox claim.
  - DB-09: compose volume mounted at `.../data`. Existing deployments: `pg_dump` first.
  - DB-10: email uniqueness is checked when the change is requested.
- **Backend:**
  - BE-01: contact details for logged-in users.
  - BE-02 and FE-12: seller undo, buyer "Not me", auto-revert after 7 days, and a masked-email disambiguator in the buyer picker.
  - BE-04: an anonymised user's unsold listings, favourites and media are deleted; the UI copy matches.
  - BE-05 and X-04: typed exceptions and one `{code, message, fieldErrors?}` envelope, also from the security filters.
  - BE-06: a strong `JWT_SECRET` is required outside local/test/dev.
  - BE-07: right-most untrusted `X-Forwarded-For` hop, per-endpoint write limits per token, and a login lockout.
  - BE-08: scheduler pool of 4, duplicate QR-expiry job removed, and bot calls stop for the tick after the first failure.
  - BE-09: register and resend no longer reveal which emails exist.
  - BE-10: HTTPS image-host allowlist (`MEDIA_ALLOWED_HOSTS`).
  - BE-11: upload errors surface, and uploads are checked by magic bytes.
  - BE-12: reset token moved into the URL fragment.
  - BE-13: `@CrossOrigin` removed in favour of one exact-origin CORS config, plus a CSP.
  - BE-14: disabled accounts can't post through the bot.
- **WhatsApp:**
  - WA-01: anchored login command plus YES confirmation.
  - WA-02: consent before any LLM call or media download, persisted declines, and hashed trace IDs.
  - WA-03: consent file untracked. It is still in git history.
  - WA-04: LIDs are never used as phone numbers.
  - WA-05: dedupe IDs persisted and draft timers restored on start.
  - WA-06: webhook bound to localhost.
  - WA-09: `TARGET_GROUP_JID` and `APP_BASE_URL` are now required and have no production defaults.
- **Bot:**
  - BOT-01: number replies are parsed as prices.
  - BOT-02: a seller-contact endpoint was added.
  - BOT-03: a null parse is treated as retryable.
  - BOT-04: idempotency key per draft.
  - BOT-05: `image/*` only, with null-checked downloads.
  - BOT-06: flexible YES/NO replies, nudges and a help hint.
  - BOT-07: slash-only group commands.
  - BOT-08: price parsing handles thousands and decimal separators.
  - BOT-09: per-user submission mutex.
  - BOT-10: LLM request timeouts.
  - BOT-11: the ImageMagick dependency is documented.
- **Front-end:**
  - FE-01 to FE-06 and FE-09: these were the quick wins.
  - FE-07: the header is not sticky on short screens, and the Sites row is hidden there.
  - FE-08: Edit Profile form, with logout after a phone or password change and email-change code entry.
  - FE-10: 44px touch targets.
  - FE-11 and X-07: prices in EUR.
  - FE-13: profile skeleton.
- **Cross-cutting:**
  - X-01: contact journey.
  - X-02: renewal from the web, status and expiry on the profile, and a reminder 12h ahead in local time with a link.
  - X-03: the front end parses offset-less timestamps as UTC.
  - X-05: stale tests fixed.
  - X-08: `scripts/validate-frontend-contracts.js` compares the category lists.

### Partly fixed
- **BE-03:** pending accounts are no longer recycled, and a phone is only released from an abandoned pending account (unverified, code expired). Phone ownership is still not proven via the bot at registration.
- **WA-07:** the webhook now answers 202 "accepted" rather than claiming delivery. Delivery acknowledgements are not tracked.
- **FE-14:** the CSP narrows the XSS surface. The JWT is still in `localStorage`; moving it to an HttpOnly cookie needs CSRF handling.
- **X-06:** there's a scheduler pool and the bot-failure short-circuit. systemd units, a TLS proxy, health endpoints and log rotation are deployment work outside the repo.

### Deferred, with reasons
- **DB-02/X-03, full `Instant`/`timestamptz` migration:** this touches every entity, the migrations and the API contract. The UTC `Clock` plus front-end parsing removes the user-visible skew in the meantime.
- **DB-05, lazy `@ManyToOne`:** suspected N+1 only. Measure first.
- **DB-08, `pg_trgm` index:** not needed at campus scale.
- **BE-15, per-request user lookups:** fine at current scale; it's what makes token revocation work.
- **Bot state in SQLite (refactor 22):** dedupe and timers are now persisted, but drafts with base64 media still live in the JSON file.
- **WA-08, official WhatsApp Cloud API:** a product decision with template and 24h-window implications.
- **X-09, request-ID propagation:** small, but it touches every log line in both services. Best done with structured logging.
