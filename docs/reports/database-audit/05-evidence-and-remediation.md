# Evidence, reproduction notes, and remediation plan

[Report index](README.md)

## 1. Audit conditions

Testing used only synthetic records and a new native PostgreSQL 18.6 cluster created under a unique temporary directory. Migration probes initially used a private Unix socket. A separate empty audit database was then exposed only on loopback to a temporary Spring Boot instance on port 58087, with an explicitly supplied audit JWT key and bot key. The real application's default database was never used. No SMTP/reset emails, WhatsApp messages, Cloudinary uploads, or production requests were sent.

Existing Docker/Testcontainers integration tests could not be run: Docker returned permission denied for its socket. Rather than claiming these suites passed, the audit used the available native PostgreSQL tools and actual application HTTP endpoints. These probes supplement the existing tests; they are not a full substitute for the configured PostgreSQL 15 integration suite.

The selected Gradle task ran successfully:

```bash
./gradlew test \
  --tests 'com.studentstorefront.service.*' \
  --tests 'com.studentstorefront.config.*' \
  --tests 'com.studentstorefront.scheduler.*' \
  --console=plain
```

| Existing test class | Tests | Failures/errors |
|---|---:|---:|
| UserServiceTest | 7 | 0 |
| WhatsAppQrLoginServiceTest | 21 | 0 |
| RateLimitFilterTest | 12 | 0 |
| PostExpirationSchedulerTest | 6 | 0 |
| **Total** | **46** | **0** |

The reviewed source compiled successfully; a subsequent actual fresh Spring startup applied V1–V7, initialized Hibernate validation, and served requests successfully. Fresh-start success does not imply safe legacy upgrades.

## 2. Observed evidence ledger

| Probe | Actual result | Finding |
|---|---|---|
| Apply V2 to a completely empty PostgreSQL database | Success; nine application tables created. | Architecture baseline |
| Start real Spring/Flyway on a separate empty database | Success; HTTP service started and Hibernate validation passed. | Fresh-schema viability |
| Execute documented schema plus seed | Success: 10 users under old sellers table, 18 posts, 24 media, 7 favourites, 6 reviews, 2 reset tokens, 2 verification tokens, 5 QR sessions. | Legacy fixture baseline |
| Execute V2 on that documented seeded schema | PostgreSQL error: `new row for relation "posts" violates check constraint "posts_status_check"`. | M01 |
| Execute V2 on empty documented schema, then insert USER | Migration succeeded; insertion failed with `sellers_role_check`. | M01 |
| Remove only old status CHECK in a disposable legacy copy, then migrate | Allowed inspection of later migration defects; not a proposed fix. | M02–M04 |
| Inspect imported review sequence | Max review ID 6; next sequence value 1; next insert collided at ID 2 because diagnostic nextval consumed 1. | M02 |
| Compare legacy vs migrated token row counts in that copy | Reset 2 → 0; verification 2 → 0. | M04 |
| Mimic buyer-account dependency cleanup in SQL | User delete failed with `sales_buyer_user_id_fkey`; transaction rolled back. | D01 |
| Insert invalid post inside transaction, then roll back | Accepted price -1, invalid category, invalid status, blank content. | D03 |
| Insert invalid review inside transaction, then roll back | Accepted rating 99, direction INVALID, same reviewer/reviewee. | D03 |
| Insert session for nonexistent user 9999, then roll back | Accepted completed session with no real user/required completion metadata. | D03 |
| Load a current schema manually without Flyway history, then start app | Automatic baseline followed by V2 failed on already-existing `uk_review_sale_direction`. | M05 |
| `/api/auth/me` with audit-signed token for disabled fixture | HTTP 200 and disabled user's private DTO. | S02 |
| Change enabled fixture's email via PUT | HTTP 200; database `is_enabled` remained true. | S03 |
| Insert replacement account with released email; reuse original token | HTTP 200 as replacement account ID 7 rather than original ID 2. | S02 |
| Correct bot key only on `/api/users/by-phone` | HTTP 401, “Authentication required.” | C02 |
| Create listing with image URL `not-a-url` | HTTP 201; invalid string saved and returned. | D06/S06 |
| Create listing price 12.345, then reload | Create returned 12.345; database/GET returned 12.35. | D06 |
| PUT title `""` | HTTP 200; empty title persisted. | D06 |
| Anonymous GET collection after fixture marked ARCHIVED | HTTP 200 including archived listing, seller email, and phone. | S09 |
| Invalid sort property | HTTP 500 with `No property 'notAField' found for type 'Post'`. | S10 |
| Search with quote/SQL-shaped string | HTTP 200, normal empty page. This alone is not proof against all injection. | Injection assessment |
| 16 claim requests from same source | First 15 returned 410; 16th returned 429. | Limiter positive control |
| Another claim request with new X-Forwarded-For | HTTP 410; forged forwarded address obtained a fresh budget. | S08 |
| Eight simultaneous exchanges of the same valid claim token | `[200, 500, 200, 200, 200, 200, 500, 200]`: **six successes**. | S05 |
| Evaluate actual bot springServices module with inert dependencies | `ReferenceError: resolveMediaUrlsByHash is not defined`. | C01 |
| Installed MessageMedia.fromUrl against local stub | Accepted loopback; returned `text/plain` bytes `AUDIT_LOCAL_ONLY`. No WhatsApp send. | S06 sink |
| Fetch sold fixture through actual HTTP endpoint | Included `status:'SOLD', sold:true`; `isSold` absent; anonymous buyer null. | C03; buyer redaction positive control |
| Feed that response through actual frontend adapter | Adapter output `status: { sold: false }`. | C03 |
| Inspect compiled PostRequestDTO with javap | URL annotation appeared in Kotlin metadata, but no JVM RuntimeVisible/InvisibleTypeAnnotations section was present in the inspected class. | D06 |
| Inspect Gradle Kotlin JSON-module dependency | Resolved Jackson 2 module `com.fasterxml.jackson.module:jackson-module-kotlin:2.20.2`; no Jackson 3 Kotlin module in that module-specific result. | C03 |

HTTP auth probes used synthetic locally signed tokens to isolate validator behavior. The first probe attempt used the wrong audit-key byte length and received 401; it was corrected to match the explicitly configured key before recording the successful evidence above. No claim of default-key exploitation is based on those probes.

## 3. Minimal reproduction recipes

These examples are for a disposable database/app only. The report does not provide commands targeting a configured production database or a real person's credentials.

### Legacy upgrade failure

Create an empty disposable database and execute:

```text
1. docs/database/schema.sql
2. docs/database/seed.sql
3. BEGIN;
   [contents of src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql]
   COMMIT;
```

Use `psql -X -v ON_ERROR_STOP=1` and an explicit audit socket/host/database for each operation. Expected first failure: `posts_status_check`. On a second disposable database with schema but no seed, the migration completes; an ordinary USER insertion fails because the old role check survived.

For later sequence tests only, use a third disposable copy and remove its old `posts_status_check` before V2. Then inspect:

```sql
SELECT MAX(review_id) FROM reviews;
SELECT nextval(pg_get_serial_sequence('reviews', 'review_id'));
```

`nextval` advances the sequence even if you later roll back, so run this only in the disposable fixture. The migration must advance it to the imported maximum before normal inserts resume.

### Buyer deletion and invalid data

In the fresh migrated audit database, create users 1 and 2, a post owned by 1 with buyer 2, and its sale. The critical cleanup steps are:

```sql
BEGIN;
-- The complete service also deletes tokens, sessions, favourites, and reviews.
UPDATE posts SET buyer_user_id = NULL WHERE buyer_user_id = 2;
DELETE FROM users WHERE user_id = 2;
-- Expected: sales_buyer_user_id_fkey violation.
ROLLBACK;
```

To test database review safeguards separately, use a real sale fixture and attempt:

```sql
BEGIN;
INSERT INTO reviews
  (sale_id, reviewer_user_id, reviewee_user_id, direction, rating)
VALUES
  (1, 1, 1, 'INVALID', 99);
-- Reviewed fresh schema accepts this; corrected schema should reject it.
ROLLBACK;
```

This is a database invariant test, not a public HTTP validation test.

### One-time claim concurrency

Using a local app with synthetic data, create one COMPLETED QR session associated with an enabled fixture, a claim UUID, and a current completion timestamp. Start eight worker requests simultaneously with a barrier; all POST the same `claimToken` to `/api/auth/whatsapp/claim`. Record only HTTP status, not JWT contents. A corrected implementation must return exactly one successful exchange. The reviewed app returned six.

The request set stayed below the per-minute claim budget for its audit IP bucket. The test therefore does not rely on a high-volume attack or rate-limit exhaustion to reproduce token reuse.

### Offline bot module smoke test

This runs the real module body while preventing dotenv/environment reads or HTTP calls:

```javascript
const fs = require('fs');
const vm = require('vm');
const source = fs.readFileSync('bot/src/services/springServices.js', 'utf8');
const fakeRequire = id => {
  if (id === 'axios') return {};
  if (id === 'dotenv') return { config() {} };
  if (id === 'path') return require('path');
  throw new Error(`Unexpected dependency: ${id}`);
};
vm.runInNewContext(source, {
  require: fakeRequire,
  __dirname: '/audit',
  module: { exports: {} },
  process: { env: {
    SPRING_BASE_URL: 'http://audit.invalid',
    BOT_API_KEY: 'audit-only'
  } },
  console
});
```

Expected reviewed result: undefined `resolveMediaUrlsByHash`. An offline load test catches what a parser-only syntax check cannot.

### API-to-frontend contract probe

Fetch a sold fixture via the application's actual HTTP converter; do not serialize it using a separate test-only ObjectMapper. Pass that JSON into the actual `catalogItemFromPost` adapter. Assert its `status.sold` is true and that profile action conditions use the same field contract. Include favourite and redacted buyer fields too.

## 4. Remediation sequence

### Phase 1 — Close identity and input trust defects

1. **S01/S11:** require non-default secrets and verify runtime DB privileges/network scope in the deployment. This review did not inspect deployed values.
2. **S02/S03/S04:** immutable user IDs in JWT subjects, explicit account-state checks, token revocation/versioning, verified email/phone changes, recent authentication.
3. **S05/S08:** atomic one-time credential consumption, per-challenge attempts, trusted proxy identity, and bounded rate limits.
4. **S06/S07/D06:** controlled asset URLs/fetch destinations, safe temporary files, effective validation, and canonical monetary precision.

Each phase should ship with regression tests for the demonstrated behavior, not only tests that call mocks and assert the same method sequence.

### Phase 2 — Make upgrades and transaction history dependable

1. **M01–M05:** preflight existing schemas, reconcile enum values/constraints, preserve or explicitly quarantine legacy records, reset sequences, plan token invalidation, and remove implicit baseline assumptions.
2. **D01/D02/D03:** decide canonical sale state and account-deletion policy; add versions/atomic transitions and named relational constraints.
3. **D04:** decide buyer confirmation and immutable transaction/review retention before exposing ratings as transaction evidence.
4. Run fresh install, supplied legacy seed upgrade, older incomplete data, and rollback/restore exercises on the declared PostgreSQL major.

Do not blindly add cascading deletes to make one failing deletion test pass. That could convert a visible FK failure into silent destruction of counterparties' history.

### Phase 3 — Restore cross-application contracts

1. **C01/C02:** fix module loading, bot authentication, failure classification, and sender-bound renewal.
2. **C03:** stabilize real HTTP JSON and frontend consumers with an end-to-end contract fixture.
3. **C04/D08:** complete or remove persistent media deduplication; preserve retained asset identity; implement submission idempotency if retries are expected.
4. **D05/D07:** outbox/retry/delivery state, asset cleanup, and bounded credential/state retention.

The SSRF restriction should accompany or precede bot restoration; the current startup failure is not a durable security control.

### Phase 4 — Reduce database work and maintenance cost

1. **P01/P02:** batch DTO reads and compute summaries in SQL.
2. **P03/P04:** benchmark representative queries, apply measured indexes, bound result sizes/jobs, and stabilize pagination.
3. **C05/C06:** remove confirmed unused code/dependencies, adopt consistent entity/time conventions, generate current docs, and expand CI coverage.

Do not delete all metadata timestamps or merge all UUID fields merely to minimize schema size. Retain fields with real audit/lifecycle needs and remove only those whose consumers and retention purpose have been assessed.

## 5. Required regression coverage

| Area | Minimum meaningful cases |
|---|---|
| Migrations | Empty install; documented seeded upgrade; old SELLER conversion; sold/missing-buyer reviews; explicit high review IDs; unknown baseline; checksum/history handling. |
| Deletion | Buyer-only, seller-only, both roles, existing reviews/favourites/media/tokens/sessions; policy-preserving counterpart history. |
| Auth | Disabled JWT; old token after password reset; email change verification; deleted/reused email; concurrent QR claim/reset/verify; stale confirmation and claim deadlines. |
| Abuse limits | Untrusted forwarded header; registration spam; per-code attempt ceiling; shared budgets across instances. |
| Inputs | Invalid/unsafe media URL, redirects/private destinations, traversal filenames without touching real paths, upload failure cleanup, blank updates, monetary scale, image limits. |
| Sale/reviews | Concurrent edits/reminders/sale; unilateral buyer nomination policy; immutable history; delete-to-remove-rating behavior. |
| Contracts | Actual HTTP sold/favourite booleans; buyer redaction; bot API key lookup; module export smoke; renewal message to command flow. |
| Delivery | Rollback before notification, downstream outage, retry, duplicate workers, partial upload/submission success. |
| Performance | Query count independent of page size; SQL aggregation; paginated pending/favourite lists; measured plans on realistic cardinalities. |
| Retention | Cleanup without successful user traffic; stale completed QR sessions; bot and external-asset lifecycle. |

## 6. Limits and remaining uncertainty

- No production row counts, personal data, database permissions, backups, network topology, deployed secrets, or production logs were inspected. Runtime exposure of default secrets/DB settings remains conditional.
- No dependency-wide CVE audit or browser penetration test was performed. Dependency presence/versions were used only where directly relevant; no unverified vulnerability advisory is asserted.
- No production-load benchmark or EXPLAIN measurements were made. Index recommendations require workload-specific validation.
- The upload traversal conclusion comes from the reachable helper's path construction, not a destructive write test. The SSRF reproduction used only a harmless loopback stub; the full WhatsApp chain was not run because the bot itself is broken and no real messaging was authorized.
- Password-reset/email-code concurrency and stale Post merge races were traced in source; only QR claim concurrency was exercised through HTTP.
- PostgreSQL catalog probes tested DDL constraints independently of HTTP validation. They do not imply attackers have direct SQL access.
- Existing uncommitted application changes were preserved. Results describe the inspected working tree and may change if those edits continue.

## 7. Review coverage and source identity

The core review covered `entity/`, `repository/`, `controller/`, `config/`, `dto/`, `service/`, `scheduler/`, V1–V7, runtime properties/build configuration, database documentation, relevant bot modules, frontend adapters/templates, and existing tests/CI. Reference searches included SQL construction, native-query APIs, `@Query`, shell/eval sinks, HTML insertion sinks, model ownership fields, credential consumption, foreign-key cleanup, media hashes, and unused repository methods.

The audit-generated files are confined to this report folder. Build/test tools also refreshed ordinary ignored build outputs; isolated test data stayed in temporary storage. The remediation pass that followed this audit changed application source, migrations, seeds, and existing documentation; those changes are part of the current working tree and are listed in the repository diff.

Selected source SHA-256 fingerprints distinguish this working-tree review from the base commit:

| Source | SHA-256 |
|---|---|
| [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql>) | `b40afe6ca0a75387692e03068e41bb0172951c88eb71964a2f9b7247aa97e46e` |
| [src/main/kotlin/com/studentstorefront/service/PostService.kt](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt>) | `0bab8ca6bac2bb09a811398982f7034b14c5b24529e2fee4389ee8582ef18b2b` |
| [src/main/kotlin/com/studentstorefront/service/UserService.kt](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/UserService.kt>) | `1c063f8dd3e121d0e4fcafc1158f87f8ff4515c372eb4a46415e02bafe94e101` |
| [src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt>) | `24bb7f978fde11d7a7780e8f7ad31abb65d7c51753201fd0aed9c56950b3bdfa` |
| [src/main/kotlin/com/studentstorefront/config/JwtAuthenticationFilter.kt](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/config/JwtAuthenticationFilter.kt>) | `5555dcfeae41da9844f1e83b2bc0fcf1bfb6c5ab1386d91458191b689ddf11a9` |
| [src/main/kotlin/com/studentstorefront/service/JwtService.kt](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/JwtService.kt>) | `a0a789b5dfe26b9a6123b7b3493df6b034e5a9865c7234bea23c8fb1fb144879` |
| [src/main/kotlin/com/studentstorefront/service/CloudinaryService.kt](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/CloudinaryService.kt>) | `b3ac9bb0c8a12bfe6201a5a11c738d5fceeba86e23a7e30ec04f1f7d6736aa14` |
| [bot/src/services/springServices.js](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/services/springServices.js>) | `7bc24735b3fe36233adeb903fcb42d9b5c721061e017ea6665ed87820eea13c2` |
| [bot/src/shopping/WhatsAppShoppingChat.js](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/shopping/WhatsAppShoppingChat.js>) | `74fcaf9a591b9c6d86ad8d997c5cfed106e70a8ab0ff8a20b426518fed7d687e` |
| [src/main/kotlin/com/studentstorefront/dto/response/PostResponseDTO.kt](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/dto/response/PostResponseDTO.kt>) | `e4d57640ec44bfe1d4fb2532980f0cadcc1eb0f58f64c76b832b56421513d740` |
| [src/main/resources/static/js/core/content-adapters.js](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/static/js/core/content-adapters.js>) | `a05cb2650977d8cf65d6bd799ffa31f73e531d87b79331c9ff65c706f7723da8` |
| [docs/database/schema.sql](</home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/schema.sql>) | `ec4be61a3b3f69578b1a4bd6ef3e0d965ba805888de724ed070aee3af9957126` |
| [build.gradle.kts](</home/zach/Desktop/productivity/Programming/SDE Sales App/build.gradle.kts>) | `30fa1e3371d522e75e9fd1f25e64b19abf6ac77367e25a665294f7ae21486887` |
