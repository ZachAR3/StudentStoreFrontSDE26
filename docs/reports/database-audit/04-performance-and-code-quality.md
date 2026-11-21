# Performance, dead code, and consistency

[Report index](README.md)

Performance findings below identify query shapes and missing controls. They are not fabricated latency measurements: production cardinalities, traffic, query plans, and index statistics were not available.

## P01 — Listing response mapping performs queries per result row

**Severity: Medium. Confidence: source-confirmed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/PostService.kt:358](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:358>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:392](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:392>), [src/main/kotlin/com/studentstorefront/entity/Post.kt:34](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/entity/Post.kt:34>), [src/main/kotlin/com/studentstorefront/entity/PostMedia.kt:7](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/entity/PostMedia.kt:7>).

Each post mapped to a DTO triggers a separate media lookup. Authenticated responses also perform an `exists` query for that post's favourite state. When the viewer is neither seller nor buyer, the buyer-visibility expression can additionally query the current user to check ADMIN status. Post-to-user/buyer associations default to eager loading; Sale and Review have further eager to-one relationships. Exact SQL counts depend on Hibernate and persistence-context reuse, but the explicit media/favourite repository calls already grow linearly with page size.

A 20-post response can require 20 media queries plus 20 favourite-existence queries beyond its page query/count and identity lookups. The performance problem is query multiplication, not that pagination is entirely absent. Favourite responses already know the posts are favourited yet call the same mapper and recheck each association.

**Fix:** fetch the page of IDs/content first, fetch all media for those IDs in one ordered query, fetch favourite IDs in one query, and assemble DTOs. Load the current principal/role once. Use projections or targeted entity graphs for to-one relations; avoid collection fetch joins that accidentally break pagination. Make relationship fetch plans explicit.

**Acceptance:** query-count tests for pages of 1 and 20 items show a bounded number of queries rather than one query per item; returned ordering, buyer redaction, and favourite state remain correct.

## P02 — Rating summaries and pending reviews load whole histories

**Severity: Medium. Confidence: source-confirmed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/ReviewService.kt:80](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/ReviewService.kt:80>), [src/main/kotlin/com/studentstorefront/service/ReviewService.kt:104](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/ReviewService.kt:104>), [src/main/kotlin/com/studentstorefront/repository/ReviewRepository.kt:13](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/repository/ReviewRepository.kt:13>).

Profile summaries fetch every seller-direction review and every buyer-direction review as entities, then compute averages in Kotlin. Those entities can load their sales/posts/users, even though the summary needs only count and average. The separate recent-review query is limited to five, but that does not limit the two summary queries.

Pending review contexts fetch all sales as seller and buyer, then issue an existence query per sale to discard those already reviewed. Memory and SQL work grow with lifetime history; the endpoint returns an unpaginated list.

**Fix:** use grouped SQL/JPQL aggregate projections for averages/counts and a paginated `NOT EXISTS` query for unreviewed transactions. Fetch only the labels/IDs needed for the response. Keep recent-review ordering deterministic with a tie-breaker.

**Acceptance:** a heavily reviewed profile does not instantiate all historical Review/Sale/Post entities; pending-review queries remain bounded and contain only eligible unreviewed sales.

## P03 — Runtime indexes omit the documented dominant query paths

**Severity: Medium. Confidence: catalog inspection plus source-confirmed workload.**

**Evidence:** [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:192](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:192>), [docs/database/schema.sql:107](</home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/schema.sql:107>), [src/main/kotlin/com/studentstorefront/repository/PostRepository.kt:35](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/repository/PostRepository.kt:35>).

The fresh migration has six explicit non-unique indexes: post seller/buyer, post `(status,is_sold)`, media post, and sale seller/buyer. Primary and unique constraints create additional indexes, correctly supporting identity lookup, favourite pair uniqueness, sale-by-post, review sale/direction, email/phone, and QR UUID lookups. The stronger index set in the course SQL is not applied to fresh runtime databases.

| Query | Present support | Missing/poor match and proposed candidate |
|---|---|---|
| Active unsold newest listings | `(status,is_sold)` | Does not provide newest-first order; consider partial `(created_at DESC,post_id DESC)` for active unsold rows. |
| Category + active unsold newest | Same status index | Consider partial `(category,created_at DESC,post_id DESC)`. |
| Reminder/expiry scans | Status index | No expiry range index. Separate reminder and archive predicates if workload justifies them. |
| Profile reviews | Unique `(sale_id,direction)` | No reviewee lookup/order support; consider `(reviewee_user_id,direction)` for summaries and `(reviewee_user_id,created_at DESC,review_id DESC)` for recent reviews. |
| Deletion by reviewer or reviewee | No relevant leading key | Evaluate indexes on each referenced participant, coordinated with profile indexes. |
| Favourites for deleted post | Unique `(user_id,post_id)` | Post-only deletion is not its leading-key use case; consider `post_id`. |
| Verification latest unused per user | Primary key only | Consider `(user_id,used,created_at DESC)` matching the actual query. |
| Token cleanup / per-user deletion | Unique reset hash only | Evaluate `user_id` and `expires_at` indexes; avoid adding unused composite indexes by habit. |
| QR expiry and user deletion | UUID unique indexes | Evaluate `(status,expires_at)` and `user_id`. |
| Media ordered per post | `post_id` | Sorting currently occurs in Kotlin; an ordered query and `(post_id,display_order,id)` may help, depending on cover policy. |

Leading-wildcard `LOWER(title) LIKE '%q%' OR LOWER(description) LIKE '%q%'` cannot be efficiently served by ordinary prefix B-tree indexes. The docs' optional trigram index on `LOWER(title || ' ' || description)` does not match those separate expressions. Likewise, a concatenated name/email index does not match the current name-only buyer query. Choose either matching per-expression trigram indexes or deliberately change query semantics; measure with representative data.

Some docs indexes duplicate coverage: `(post_id,display_order)` is already backed by its unique constraint, so a second identical non-unique index adds write/storage cost. The old and new favourite unique constraint names can also coexist after rename, creating duplicate indexes on the same columns.

**Fix:** implement selected indexes through actual Flyway migrations after comparing query plans and write cost. PostgreSQL's [constraint documentation](https://www.postgresql.org/docs/current/ddl-constraints.html) distinguishes automatically indexed uniqueness from referencing foreign keys, which do not automatically get indexes. Do not blanket-index every column or present a low-cardinality status index as a complete strategy.

**Acceptance:** capture `EXPLAIN (ANALYZE, BUFFERS)` for representative search/list/profile/cleanup queries; verify catalog equality between fresh and upgraded schemas; remove exact redundant indexes only after checking constraint ownership and real usage.

## P04 — Several reads and scheduled batches have no application-level work budget

**Severity: Medium. Confidence: source-confirmed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/controller/PostController.kt:67](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/controller/PostController.kt:67>), [src/main/kotlin/com/studentstorefront/service/FavouriteService.kt:23](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/FavouriteService.kt:23>), [src/main/kotlin/com/studentstorefront/repository/PostRepository.kt:66](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/repository/PostRepository.kt:66>), [src/main/kotlin/com/studentstorefront/scheduler/PostExpirationScheduler.kt:20](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/scheduler/PostExpirationScheduler.kt:20>).

Public pageable endpoints pass client sorting/page size through without a domain allowlist or explicitly configured small maximum. Framework pagination has its own defaults/cap; this report does not claim page size is literally unlimited. Large allowed pages still amplify P01, count queries, and offset scans. Several routes have no stable default ordering, and the newest-first search has no unique tie-breaker.

Favourites and pending reviews are unpaginated. The reminder job selects all due posts, issues asynchronous notifications for all of them, and processes the batch in one transaction. There is no persisted job claim or cluster coordination; multiple instances run the same scheduled methods.

**Fix:** set explicit endpoint page-size limits and allowed sorts, add primary-key tie-breakers, and consider cursor pagination when large offsets become relevant. Bound background batches and concurrency; use durable job claiming/idempotency for multiple workers. Put query timeouts and workload measurements in deployment configuration rather than guessing universal limits.

**Acceptance:** synthetic large fixtures do not produce unlimited result collections or notification fan-out; pagination is stable across ties; two schedulers do not process the same job concurrently.

## C01 — The bot crashes before reaching the database API

**Severity: High availability defect. Confidence: reproduced without external services.**

**Evidence:** [bot/src/services/springServices.js:165](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/services/springServices.js:165>), [bot/src/WhatsappBot.js:8](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/WhatsappBot.js:8>).

`module.exports` references `resolveMediaUrlsByHash`, but the function is not defined anywhere in that module. Evaluating the real source with mocked axios/dotenv and inert configuration throws `ReferenceError: resolveMediaUrlsByHash is not defined`. `WhatsappBot.js` imports it near the top, before its later exception handlers are registered. This prevents registration handoff, listing submission, and WhatsApp login/shopping in the normal bot process.

**Fix:** complete or remove the abandoned resolver contract consistently (C04); add an offline module-load smoke test that uses the real export/import graph.

**Acceptance:** starting/importing the bot modules succeeds with external clients stubbed, and every exported callable is defined. Syntax-only checks are insufficient because this file parses successfully.

## C02 — Bot lookup authentication and renewal contracts are inconsistent

**Severity: High for bot lookup; Medium for renewal. Confidence: lookup reproduced, renewal source-confirmed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/config/SecurityConfig.kt:27](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/config/SecurityConfig.kt:27>), [src/main/kotlin/com/studentstorefront/controller/UserController.kt:75](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/controller/UserController.kt:75>), [bot/src/services/springServices.js:52](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/services/springServices.js:52>), [src/main/kotlin/com/studentstorefront/controller/PostController.kt:57](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/controller/PostController.kt:57>), [src/main/kotlin/com/studentstorefront/service/BotNotificationService.kt:25](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/BotNotificationService.kt:25>).

The bot calls `/api/users/by-phone` with only `X-Bot-Api-Key`, and the controller checks that key. The filter chain has no matching exception or bot authentication provider, so `.anyRequest().authenticated()` rejects the request before that check runs. The local probe with the correct audit key returned 401 “Authentication required.” The bot converts errors to null, making an outage look like “not registered.” Fixing C01 alone therefore does not restore submissions.

Renewal reminders instruct users to reply `RENEW <id>` before expiry, but no corresponding command/API client implementation was found in the reviewed bot. The backend also rejects renewal while a post is still ACTIVE, exactly when the reminder is sent. Its renewal request carries only a post ID and shared bot key, with no verified sender identity/ownership check. A later command handler must not blindly forward arbitrary IDs.

**Fix:** represent bot authentication explicitly, with narrow route scopes; keep controller/service authorization aligned. Distinguish lookup outages from genuine 404 results. Implement a sender-bound renewal flow and define whether the pre-expiry reminder permits renewal immediately or only after archive.

**Acceptance:** real bot headers reach only intended bot endpoints; no-key/wrong-key callers fail; backend outage does not prompt false signup; reminder-to-renewal works and rejects other users' listings.

## C03 — Runtime JSON names do not match frontend sale-state fields

**Severity: High functional inconsistency. Confidence: reproduced through real HTTP response and actual adapter.**

**Evidence:** [src/main/kotlin/com/studentstorefront/dto/response/PostResponseDTO.kt:15](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/dto/response/PostResponseDTO.kt:15>), [src/main/resources/static/js/core/content-adapters.js:15](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/static/js/core/content-adapters.js:15>), [src/main/resources/static/index.html:555](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/static/index.html:555>), [build.gradle.kts:35](</home/zach/Desktop/productivity/Programming/SDE Sales App/build.gradle.kts:35>).

The real Spring endpoint serialized Kotlin properties as `sold` and `favourited`. The frontend reads `post.isSold`. Feeding the actual response `{status:'SOLD', sold:true, ...}` to `catalogItemFromPost` produced `{sold:false}`. Profile templates likewise show the Sold action when `!post.isSold` and hide review controls when `post.isSold` is missing. Persisted state is correct in this reproduction; the presentation contract is wrong.

The project uses Spring Boot 4.0.3, but explicitly includes the Jackson 2 Kotlin module (`com.fasterxml...`). Gradle resolved only `com.fasterxml.jackson.module:jackson-module-kotlin:2.20.2` in the module-specific insight. Boot's [JSON documentation](https://docs.spring.io/spring-boot/reference/features/json.html) describes its Jackson 3 support. This dependency/mapper mismatch should be resolved deliberately; adding annotations or testing a separately constructed Jackson 2 ObjectMapper does not prove the HTTP mapper is configured correctly.

**Fix:** choose and explicitly test one public JSON contract, including both booleans and nullable/default properties. Align Kotlin support with the HTTP serializer in use or configure property names on the intended mapper. Update all consumers together. Preserve the public buyer-redaction behavior while changing serialization.

**Acceptance:** capture actual HTTP JSON for active/sold/favourited posts and run frontend consumer assertions on it; the sold badge, available actions, and buyer review controls agree with database state.

## C04 — Media deduplication is an unfinished cross-layer feature

**Severity: Medium, plus C01's startup failure. Confidence: source-confirmed.**

**Evidence:** [bot/src/services/ListingSubmissionService.js:167](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/services/ListingSubmissionService.js:167>), [bot/src/services/ListingSubmissionService.js:257](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/services/ListingSubmissionService.js:257>), [bot/src/services/springServices.js:15](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/services/springServices.js:15>), [src/main/kotlin/com/studentstorefront/repository/PostMediaRepository.kt:9](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/repository/PostMediaRepository.kt:9>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:269](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:269>).

The bot computes SHA-256 values and passes a fourth `hashes` argument to `createPost`; that function accepts only three arguments and sends no hash data. `existingByHash` is always an empty object. Deduplication therefore only reuses uploads within one draft through `uploadedByHash`; it never queries stored backend hashes. The backend request has no hashes, inserts PostMedia with null imageHash, and exposes no advertised `/bot/media/resolve` endpoint. The repository lookup exists without callers.

This is not a functioning persistent deduplication feature with a merely missing index. It consumes code complexity while the promised path is disconnected. Add an image-hash index only if the feature is completed. Updating media currently destroys any historical hash values.

Submission also lacks durable source-message/idempotency keys. Multi-item drafts are posted one request at a time. A network failure after one successful commit can leave partial posts; retry can create duplicates. The bot's processed-message set is in-memory, not a durable database guarantee.

**Fix:** either remove the abandoned persistent-hash contract and document local-only reuse, or complete typed request/response contracts, content validation, asset lookup, and a coherent reference model. Independently add durable per-item idempotency keys if retries must be safe.

**Acceptance:** repeated bytes across separate drafts reuse assets when advertised; hashes reach persisted records and survive edits; retrying an interrupted draft neither duplicates successful items nor loses the remaining items.

## C05 — ORM conventions and dead code obscure the actual domain

**Severity: Low to Medium. Confidence: source inspection and reference searches.**

| Location | Specific issue | Appropriate action |
|---|---|---|
| [src/main/kotlin/com/studentstorefront/service/PostService.kt:269](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:269>) | A private write helper has `@Transactional(readOnly=true)`. Self-invocation through a private method does not apply separate proxy advice; the outer transaction actually controls it. | Remove the misleading annotation. This is not evidence that writes currently run in a read-only transaction. Spring documents [proxy/self-invocation semantics](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html). |
| [src/main/kotlin/com/studentstorefront/entity/Post.kt:11](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/entity/Post.kt:11>), [src/main/kotlin/com/studentstorefront/entity/User.kt:9](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/entity/User.kt:9>) | JPA entities are Kotlin data classes with generated equality/hash/toString across state and associations. Bidirectional Post/Media traversal can recursively expand if both sides are included; User.toString includes the password hash. Mutable fields make value-based hash keys unstable. | Use entity identity semantics and explicit non-sensitive toString; avoid logging complete entities. No current stack overflow or hash leak was demonstrated. |
| [src/main/kotlin/com/studentstorefront/service/PostService.kt:194](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:194>), [src/main/kotlin/com/studentstorefront/service/UserService.kt:225](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/UserService.kt:225>) | Entity `copy` + `save` merges detached-style snapshots instead of changing only intended managed fields, amplifying stale-write risks. | Adopt consistent update semantics with optimistic versioning; test concurrent security and lifecycle changes. |
| [src/main/kotlin/com/studentstorefront/repository/PostRepository.kt:28](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/repository/PostRepository.kt:28>) | `findByUserUserIdAndIsSoldTrue` and `findByBuyerUserIdAndIsSoldTrue` have no current source callers, superseded by sales queries. | Remove after checking external/test use; keep Sale queries as the intentional transaction path. |
| [src/main/kotlin/com/studentstorefront/repository/ReviewRepository.kt:12](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/repository/ReviewRepository.kt:12>) | `findBySaleId` has no caller in current application/tests. | Remove if no planned use; avoid retaining speculative repository APIs. |
| [src/main/kotlin/com/studentstorefront/repository/PostMediaRepository.kt:3](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/repository/PostMediaRepository.kt:3>) | Unused Post import; hash lookup is disconnected. | Remove import and address hash lookup with C04. |
| [src/main/kotlin/com/studentstorefront/service/PostService.kt:372](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:372>) | Elvis fallback on non-null status obscures the declared invariant. | Fix any real legacy nulls in migration; avoid pretending the Kotlin field is nullable when it is not. |
| [src/main/kotlin/com/studentstorefront/service/UserService.kt:38](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/UserService.kt:38>), [src/main/kotlin/com/studentstorefront/entity/EmailVerificationToken.kt:29](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/entity/EmailVerificationToken.kt:29>) | Duplicate secure code generators and repeated phone/LIKE/current-user helpers. Old seller variable/method names remain throughout new User/Sale code. | Centralize identity normalization and authentication context; use one code generator; finish domain naming consistently. |
| [build.gradle.kts:29](</home/zach/Desktop/productivity/Programming/SDE Sales App/build.gradle.kts:29>), [build.gradle.kts:55](</home/zach/Desktop/productivity/Programming/SDE Sales App/build.gradle.kts:55>) | No application WebSocket/STOMP handlers or Quartz jobs found. The audit startup nevertheless created a Quartz RAM scheduler; actual jobs use Spring `@Scheduled`. | Remove unused starters if no external configuration needs them; keep WebFlux because WebClient is used. |

Do not classify `Sale`, `isSold`, `buyer`, `isCover`, or credential `used` as dead merely because they are redundant or poorly designed. They have real callers. The complete field inventory identifies which removals need migration and consumer changes.

## C06 — Tests, documentation, and operational assumptions do not agree

**Severity: Medium. Confidence: source-confirmed plus validation results.**

**Evidence:** [docs/REPOMAP.md:1](</home/zach/Desktop/productivity/Programming/SDE Sales App/docs/REPOMAP.md:1>), [docs/database/schema.sql:1](</home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/schema.sql:1>), [.github/workflows/ci.yml:1](</home/zach/Desktop/productivity/Programming/SDE Sales App/.github/workflows/ci.yml:1>), [scripts/validate-frontend-contracts.js:28](</home/zach/Desktop/productivity/Programming/SDE Sales App/scripts/validate-frontend-contracts.js:28>), [bot/package.json:6](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/package.json:6>), [docker-compose.yaml:3](</home/zach/Desktop/productivity/Programming/SDE Sales App/docker-compose.yaml:3>).

The repo map omits or misstates the live Sale/Review/token fields, public archive behavior, buyer-search scope, favourite payloads, and removed media resolver. The diagram/seed/query package describes the old schema. `DataLoader` is described as legacy repairs but actually just creates a disabled local demo user with a weak demo password; it is not a migration repair mechanism. Admin user creation also uses the disabled default and sends no code, so “create directly” does not mean an immediately usable account; a separate resend/verification is required.

Existing service/config/scheduler unit tests pass, but they do not expose the database migration/deletion/concurrency/serialization defects reproduced in this audit. Controller integration tests use real PostgreSQL containers but primarily fresh installs and sequential workflows, with mocked security principals in several paths. The frontend “contract” checker validates layout JSON registries, not backend HTTP DTOs. The bot's npm test command is a failing placeholder, and CI runs Gradle only.

Compose uses an unpinned `postgres`, while CI and Testcontainers use PostgreSQL 15. An unpinned major can change data-directory conventions and upgrade requirements; a persistent volume is not a backup or major-version migration plan. The [official image documentation](https://raw.githubusercontent.com/docker-library/docs/master/postgres/README.md) describes version-specific storage and initialization behavior. All application timestamps are `LocalDateTime`/SQL TIMESTAMP without timezone; application clocks, database defaults, and bot UTC strings can disagree. The bot's posted expiresAt is ignored by the backend DTO, making the apparent cross-system expiry control misleading.

Bot state is a second persistence system: [bot/src/services/BotStateStore.js:32](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/services/BotStateStore.js:32>) stores phone-keyed drafts/messages/media and registration flags in JSON. Main state uses temp-file rename, which is a positive atomic-write measure, but load errors are swallowed and whole-file synchronous saves are process-local. Consent uses a separate direct write in [bot/src/WhatsappBot.js:53](</home/zach/Desktop/productivity/Programming/SDE Sales App/bot/src/WhatsappBot.js:53>). There is no backend account-deletion notification to remove these records or external images. This does not mean the PostgreSQL transaction can cover them; their lifecycle must be deliberately coordinated.

**Fix:** generate current schema/DTO documentation, add upgrade and concurrency fixtures, test actual HTTP serialization and bot module loading, pin database versions, and establish UTC/Instant semantics with an injectable Clock. Make demo/admin activation paths explicit. Treat bot JSON as a documented single-instance store with observable failures and lifecycle cleanup, or move it to a transactional store if multiple workers are needed.

**Acceptance:** CI tests the failures listed in the evidence report, docs are generated/compared against current migrations and routes, all supported environments use a declared database major, and time/retention behavior is deterministic.
