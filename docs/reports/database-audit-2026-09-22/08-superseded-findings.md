# Superseded findings from the 2026-05-14 audit

[Report index](README.md)

The previous audit (`../../database-audit-2026-05-14.md` and `../../database-audit/`)
was written against the pre-hardening tree (`ddl-auto` concerns, `sellers` naming,
no triggers/outbox/confirmation flow, no locks on token consumption). The codebase
has since been substantially reworked. This file maps every old finding to its
current state so nothing is lost and nothing is double-reported.

Legend: **Fixed** — verified resolved in current code · **Partial** — mitigated,
residual captured as a current `V/I/Q/P` finding · **Open** — carried into this
report.

| Old | Topic | Status now | Evidence / carried forward as |
| --- | --- | --- | --- |
| S01 | Known fallback secrets (JWT, bot key) | **Partial** | `SecretsConfiguration` now fails startup on weak secrets in prod; but `JwtService` still silently falls back to a random key when `jwt.secret` is blank → **V-2** |
| S02 | JWT ignored `isEnabled`, subject= mutable email, no revocation | **Fixed** | `isTokenValid` checks `uid` + `ver` (`token_version`) + `isEnabled` + expiry; uid binds identity to the immutable id; `tokenVersion` bump on password/phone change and anonymization |
| S03 | Email change bypassed verification | **Fixed** | `pendingEmail` + `targetEmail` verification flow; self-credential changes require `currentPassword`; email swap only after code verification |
| S04 | Phone treated as verified without proof | **Partial** | `phoneVerifiedAt` gate on `GET /api/users/by-phone`; registration still binds phones unauthenticated → **V-6** |
| S05 | One-time credentials raceable (QR claim, reset, verify) | **Fixed** | `@Lock(PESSIMISTIC_WRITE)` on `findByTokenHash`, `findTopBy…Verification…`, `findBySessionId/LoginToken/ClaimToken`, outbox claim; unique constraints back the remaining races |
| S06 | Stored media URL → bot-side SSRF fetch | **Partial** | `MediaUrlPolicy` (HTTPS, no userinfo, private/loopback DNS rejection) at write time; fetch-time DNS rebinding residue → **I-5** |
| S07 | `originalFilename` temp-path traversal in upload | **Fixed** | `CloudinaryService.convertMultiPartToFile` uses `Files.createTempFile` + `finally` deletion; upload rollback compensates via `AfterCommit.onRollback` |
| S08 | Rate limiter bypassable; registration unprotected | **Partial** | Registration/resend/verify now in the limits map; shared DB windows; bounded Caffeine cache; trusted-proxy config — but leftmost `X-Forwarded-For` parsing remains spoofable → **V-1** |
| S09 | Public listing reads leaked archived rows + contact PII | **Fixed** | `isVisibleToViewer` restricts archived/sold posts to owner/buyer/admin; `canViewContact`/`canViewBuyer` gate email/phone/buyer in `PostResponseDTO` |
| S10 | Unhandled exceptions leaked internals | **Partial** | `GlobalExceptionHandler` now sanitizes + correlation IDs; residual allowlist drift and error-text matching → **Q-1**, **V-8** |
| S11 | Docker DB defaults / exposed 5432 | **Partial/verify** | `SecretsConfiguration` validates DB credentials in prod; verify `docker-compose.yaml` port binding and role separation in deployment (not re-audited here) |
| M01 | Legacy `sellers`→`users` upgrade path broken | **Partial** | V3 re-hardening + preflight `RAISE EXCEPTION` blocks + `migration_*_quarantine`; upgrade from a real legacy DB was not re-executed in this audit |
| M02 | Imported review IDs didn't advance the sequence | **Fixed** | V2/V3 end with `setval(pg_get_serial_sequence('reviews', 'review_id'), …)` |
| M03 | Review conversion silently dropped unmappable rows | **Fixed** | `migration_review_quarantine` captures every rejected row with a `reason`; sale quarantine likewise |
| M04 | Token tables dropped wholesale in migration | **Fixed-by-design** | V2 documents deliberate invalidation ("users receive a fresh reset link or verification code") |
| M05 | Auto-baselining hid incompatible states | **Partial** | `spring.flyway.baseline-on-migrate=false`; V2/V3 preflight blocks raise on incompatible schemas |
| D01 (old) | Buyer account deletion failed on `sales.buyer_user_id` FK | **Fixed** | `hasSales()` → `anonymizeUser()` in place; `clearBuyerReferences`; `enforce_post_sale_consistency` protects history |
| D02 (old) | Sale state duplicated, stale writes could unsell posts | **Fixed** | `@Version` on `Post`/`User`; `chk_posts_sale_state`; `enforce_post_sale_consistency`; `markAsSold` takes `findByIdForUpdate` + unique `sales.post_id` (residual flush-order note → **F-11**) |
| D03 (old) | Fresh DB accepted invalid states (rating 99, self-reviews, orphan sessions) | **Fixed** | Named CHECKs (`chk_review_rating`, `chk_review_direction`, `chk_review_distinct_users`, `chk_posts_*`, `chk_whatsapp_*`) + three integrity triggers + `whatsapp_login_sessions.user_id` FK |
| D04 (old) | Sellers could fabricate buyer participation / rewrite history | **Fixed** | Buyer-confirmation gate (`buyer_confirmed_at`) for review eligibility + review notification; `title_snapshot`/`price_snapshot`; sold posts can't be edited/deleted (service + trigger) |
| D05 (old) | DB tx didn't make external side effects reliable | **Fixed** | Transactional `notification_outbox` with lease/retry/dedupe; `AfterCommit.run/onRollback` for Cloudinary compensation; residue: payload retention → **V-3** |
| D06 (old) | DTO validation vs DB precision mismatch (URLs, price scale) | **Fixed** | `@Digits`, `normalizePrice` (`RoundingMode.UNNECESSARY`), `MediaUrlPolicy` service-level URL validation (type-use `@URL` reliance removed) |
| D07 (old) | Token/session retention unbounded | **Partial** | `CredentialCleanupScheduler` (5-min sweeps) purges/expired tokens + sessions; outbox purges delivered rows; residue: outbox payload/PII retention → **V-3**, `creator_ip` → **D-1** |
| D08 (old) | Media replacement lost identity, cover inconsistencies | **Partial** | `saveOrderedMediaPreservingIdentity`, `image_hash` + `cloudinary_public_id` with reference-checked cleanup; residue: URL-as-identity in update payloads → **Q-12** |
| Doc drift (old) | `schema.sql`/DBML/seed described `sellers`, plaintext tokens | **Partial** | Regenerated around `users`/`sales`/hashed tokens; fresh drift found (naming, `chk_posts_version`, DBML expressions) → **F-1**, **F-4**; seed constraint violation → **F-2** |
| C01/C02 (old) | Bot crash at load; phone lookup 401 | **Out of DB scope** | Bot files have changed since; bot-to-API contract spot-checked only (`X-Bot-Api-Key` flows) |
