# Migrations and data integrity

[Report index](README.md)

## M01 — V2 does not upgrade the repository's documented schema

**Severity: High. Confidence: reproduced in PostgreSQL.**

**Evidence:** [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:1](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:1>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:142](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:142>), [docs/database/schema.sql:9](</home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/schema.sql:9>), [docs/database/schema.sql:34](</home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/schema.sql:34>), [src/main/kotlin/com/studentstorefront/enums/Role.kt:1](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/enums/Role.kt:1>).

V2 renames `sellers` to `users` but leaves role values and constraints unchanged. The old schema allows only `SELLER`/`ADMIN`; the application now expects `USER`/`ADMIN`. V2 also sets sold posts to `status='SOLD'`, but the existing status check allows only ACTIVE/ARCHIVED. `CREATE TABLE IF NOT EXISTS` does not replace those existing definitions.

**Reproductions:**

- Load `docs/database/schema.sql` and `seed.sql`, then execute V2 in a transaction: migration fails with `posts_status_check` on the first sold row being updated.
- Load the old schema with no rows, then execute V2: it completes, but inserting a normal `role='USER'` account fails with `sellers_role_check`.
- Removing only the old status check in a disposable seeded copy lets later migration stages run, but old SELLER data still remains. Reading those values as the new Kotlin enum is incompatible.

Column sizes, price precision, old FK delete actions, and indexes can also remain different between upgraded and fresh databases. Fresh install success therefore gives false confidence about upgrade correctness.

**Fix:** explicitly migrate enum values and replace affected constraints before dependent updates; normalize existing column definitions and FK actions; qualify schema metadata checks. Use incremental migrations from each supported predecessor, with preflight assertions and reconciliation. Do not edit a migration already applied to deployed databases without a deliberate compatibility plan.

**Acceptance:** empty and seeded legacy databases converge to the same intended schema and data rules as a fresh install. Old ordinary users become USER and can log in; sold posts and reviews survive.

## M02 — Imported review IDs do not advance the new identity sequence

**Severity: High. Confidence: reproduced.**

**Evidence:** [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:148](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:148>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:166](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:166>).

V2 creates a new BIGSERIAL review table and copies explicit review IDs. It never aligns the new sequence with the imported maximum. In the isolated seeded upgrade after removing the blocking old status check, `MAX(review_id)` was 6 while the next sequence value was 1. A following insert collided with an existing primary key (the diagnostic nextval had consumed 1, so the observed insert collided at 2).

**Fix:** after preserving/reconciling imported IDs, reset the sequence using `pg_get_serial_sequence`, the actual maximum, and correct empty-table `is_called` semantics. Do this transactionally as part of the migration. Do not “solve” collisions by making the service retry until it gets past the old IDs.

**Acceptance:** the first newly created review after a seeded upgrade gets an unused ID; both empty and high-ID fixtures work.

## M03 — Review conversion silently discards records it cannot map

**Severity: High for affected legacy data. Confidence: source-confirmed.**

**Evidence:** [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:135](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:135>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:166](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:166>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:174](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:174>).

Sales are backfilled only for sold posts with non-null buyer IDs. Legacy reviews are copied through an inner join to those sales and with `ON CONFLICT DO NOTHING`. Any review whose post lacks a qualifying sale is omitted; a conflicting record may also be suppressed. V2 then drops the legacy table without comparing source, destination, and rejected counts. Legacy self-sales can instead abort the backfill because the new sale constraint requires distinct participants. Neither case has an explicit reconciliation policy.

**Fix:** preflight all legacy reviews and sale candidates. Abort with an actionable summary or preserve rejected rows in a quarantine/export table. Require an explicit policy for sold posts with missing buyers, duplicate directions, and inconsistent participants. Reconcile counts before removing the old table and retain a recoverable backup.

**Acceptance:** every legacy review is either mapped or explicitly retained/reported; no unmatched row disappears merely because an inner join filtered it out. Test invalid legacy data, not only clean course seeds.

## M04 — Token tables are dropped wholesale during migration

**Severity: Medium. Confidence: reproduced.**

**Evidence:** [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:105](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:105>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:115](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:115>).

The migration drops and recreates password-reset and verification tables. The old seeded database contained two of each; the migrated disposable copy contained zero. Moving from raw secrets to hashes is a sound security improvement, but wholesale invalidation is a user-visible behavioral change, not a harmless schema cleanup. Pending users remain disabled, and all in-flight codes/links stop working.

**Fix:** deliberately choose either safe hash conversion or announced invalidation/reissue. For short-lived credentials, invalidation is often preferable, but describe it in deployment instructions and make recovery dependable. Keep this separate from unrelated rename and review transformations.

**Acceptance:** pending signups and reset users have a tested recovery path after the migration; deployment documentation states that previously issued credentials are invalidated.

## M05 — Automatic baseline and “if exists” checks hide incompatible starting states

**Severity: Medium. Confidence: source-confirmed, with a startup failure observed on an untracked current schema.**

**Evidence:** [src/main/resources/application.properties:20](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/application.properties:20>), [src/main/resources/db/migration/V1__baseline_runtime_schema.sql:1](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V1__baseline_runtime_schema.sql:1>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:3](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:3>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:181](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:181>).

Automatic baselining assumes an existing nonempty database can start at the default baseline and run V2. That assumption is not validated against a schema fingerprint. Existing current-schema tables without Flyway history were also tested: startup baselined the database and attempted V2 again, then failed because `uk_review_sale_direction` already existed after the review table rename. This is a manually initialized/untracked schema scenario, not a claim that ordinary Flyway reruns V2 after a successful tracked migration.

Metadata checks filter by table/constraint name without consistently filtering schema or owning table. They can match unrelated objects on databases with multiple schemas. `ALTER TABLE sellers RENAME TO users` also fails if both names already exist. “IF EXISTS” provides conditional execution, not proof of compatibility.

**Fix:** baseline only recognized schema states through an explicit deployment procedure. Assert the expected schema/table/constraint structure before migration; scope metadata queries by schema and relation. Keep migrations deterministic for documented predecessor versions.

**Acceptance:** unsupported starting states fail before destructive changes with a useful diagnostic; supported legacy and already-tracked states behave predictably.

## D01 — Deleting a buyer account fails because sales are not cleaned up

**Severity: High. Confidence: reproduced with service-equivalent SQL.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/UserService.kt:235](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/UserService.kt:235>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:125](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:125>).

Cleanup removes user-linked tokens, sessions, favourites, and reviews; clears `posts.buyer_user_id`; and deletes owned posts. It never handles `sales.buyer_user_id` for purchases of someone else's listing. That FK is NOT NULL and has no ON DELETE action. The final user deletion fails with `sales_buyer_user_id_fkey` and the transaction rolls back. Buyer-reference cleanup was updated for the older post-only model but not the new sales model.

Deleting a seller's owned post can cascade away that sale on the fresh schema, so seller and buyer deletion do not behave symmetrically. The unit tests mock repository calls and use no actual sold-buyer FK fixture, so their success does not verify this path.

**Fix:** define whether account deletion anonymizes or removes historical transactions/reviews. Implement that policy across both sale participants and post references, preferably preserving counterparties' legitimate history. Do not simply add broad cascades without deciding the history policy.

**Acceptance:** a user who has bought, sold, reviewed, favourited, and created tokens/sessions can be deleted/anonymized according to policy; no FK error and no unintended loss of other users' history.

## D02 — Sale state is duplicated and vulnerable to stale writes

**Severity: High for inconsistent transaction state. Confidence: source-confirmed race/design defect; no post-write race stress test was executed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/PostService.kt:175](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:175>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:263](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:263>), [src/main/kotlin/com/studentstorefront/scheduler/PostExpirationScheduler.kt:23](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/scheduler/PostExpirationScheduler.kt:23>), [src/main/kotlin/com/studentstorefront/entity/Post.kt:24](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/entity/Post.kt:24>).

The sale is represented by `posts.is_sold`, `posts.status`, `posts.buyer_user_id`, `posts.sold_at`, and a `sales` row holding participants/time. Reviews use sales; public listing data uses posts. There is no database invariant enforcing agreement, no optimistic `@Version`, and no locking when making transitions.

A reminder/edit transaction can load an unsold post, another transaction can mark it sold, and the earlier transaction can merge a copied stale entity afterward. That merge can restore old sale-related fields even though the sale row exists. The unique sale-post constraint prevents two sale rows but does not protect the post from unrelated stale updates. Two simultaneous mark-sold requests also turn an intended domain conflict into a late uniqueness error.

**Fix:** choose a canonical sale representation and remove duplicated post fields in a staged migration, or formally enforce synchronization. Add optimistic versioning/conditional updates for mutable records. Reminder updates should update only reminder metadata with appropriate state predicates. Sale creation should atomically claim the listing state and return a stable conflict to losers.

**Acceptance:** concurrent edit/reminder/archive/mark-sold operations cannot leave a sale attached to an apparently unsold post or overwrite a newer buyer; only one sale is recorded and race responses are deterministic.

## D03 — The fresh database accepts invalid business states

**Severity: Medium. Confidence: missing constraints reproduced through direct SQL.**

**Evidence:** [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:58](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:58>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:90](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:90>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:148](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:148>).

The fresh schema lacks checks for valid roles/categories/statuses/review directions, positive/range-limited price, nonblank content, ratings 1–5, nonnegative media ordering, unique media position, and review participant consistency. `whatsapp_login_sessions.user_id` lacks a foreign key. The native probes accepted a negative-price invalid-category post, a self-review with rating 99 and invalid direction, and a completed session referencing user 9999 without required completion metadata.

These are database-layer gaps. Most corresponding malformed public requests are blocked by DTO/service rules; raw SQL import, future maintenance paths, bugs, and concurrent operations are not. The sale-distinct-users and review/favourite uniqueness constraints already implemented are good examples to extend. Favourite addition is only sequentially idempotent: concurrent requests can both pass the existence check and one then fails uniqueness with 409 instead of returning success. Preserve the constraint and use conflict-safe insertion if the API promises idempotency.

**Fix:** add named row checks and the missing FK; normalize existing violations before validating new constraints. For relationships across sale/post/review rows, use suitable composite keys/FKs, transaction logic with locking, or deliberate triggers—not a CHECK containing cross-table queries. PostgreSQL explains that distinction in its [constraint documentation](https://www.postgresql.org/docs/current/ddl-constraints.html).

**Acceptance:** invalid direct inserts/updates fail with named constraints, valid lifecycle transitions still work, and legacy data is reconciled before deployment.

## D04 — Sellers can rewrite transaction history and fabricate buyer participation

**Severity: Medium to High for reputation integrity. Confidence: source-confirmed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/PostService.kt:167](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:167>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:175](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:175>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:263](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:263>), [src/main/kotlin/com/studentstorefront/service/ReviewService.kt:133](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/ReviewService.kt:133>), [src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:127](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/resources/db/migration/V2__user_sales_schema_cleanup.sql:127>).

A seller can select any enabled other user as buyer without that user's acceptance. This creates review eligibility, including a seller-to-buyer review of someone who may never have transacted. The same seller can continue editing a sold post's title/price/description or delete the post. Reviews derive their displayed title from the live post, and cascade deletion removes the sale and its reviews. Deleting a negatively reviewed listing can therefore erase feedback used in profile ratings.

A lightweight marketplace may intentionally support seller-reported sales. The problem is treating that unconfirmed, editable, deletable record as durable review evidence without a defined policy.

**Fix:** require buyer confirmation before rating eligibility, or clearly mark unconfirmed records and restrict reviews. Freeze or snapshot transaction content/price; distinguish listing removal from transaction-history deletion. Preserve/anonymize review history according to an explicit policy.

**Acceptance:** unrelated buyers cannot acquire negative reviews through a seller's unilateral selection; hiding/deleting a listing cannot silently manipulate reputation; existing reviews retain the agreed item context.

## D05 — Database transactions do not make external side effects reliable

**Severity: Medium. Confidence: source-confirmed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/scheduler/PostExpirationScheduler.kt:27](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/scheduler/PostExpirationScheduler.kt:27>), [src/main/kotlin/com/studentstorefront/service/BotNotificationService.kt:29](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/BotNotificationService.kt:29>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:67](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:67>), [src/main/kotlin/com/studentstorefront/service/EmailVerificationService.kt:21](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/EmailVerificationService.kt:21>), [src/main/kotlin/com/studentstorefront/service/PasswordResetService.kt:26](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PasswordResetService.kt:26>).

Reminder delivery is asynchronous via `.subscribe()` with errors swallowed into an empty result. The scheduler immediately saves `reminder_sent_at`, even if delivery later fails. A failed reminder will not retry. Notifications can also leave the process before the transaction commits; registration webhook consumers may query before the enabled account is committed. Multiple instances can select and send the same reminder.

Image uploads occur while a database transaction is open. A later failure rolls back SQL but not a previously uploaded Cloudinary object. Media edits/deletion do not destroy unreferenced remote assets. Password-reset and resend-email methods perform synchronous SMTP within their transactions, holding a connection while external delivery waits. Initial registration correctly commits before the controller sends mail; these differing patterns should be made intentional.

**Fix:** write an outbox event in the same database transaction and deliver with bounded retries/idempotency after commit. Track delivery success separately from attempt time. For assets, use staged uploads, compensation, and reference-aware garbage collection. Move slow network work outside long database transactions.

**Acceptance:** simulated network failure retries later; rollback never sends a misleading committed-state notification; duplicate workers do not duplicate user notifications; failed or removed uploads are eventually reclaimed safely.

## D06 — Runtime input validation and database precision disagree

**Severity: Medium. Confidence: reproduced over HTTP.**

**Evidence:** [src/main/kotlin/com/studentstorefront/dto/request/PostRequestDTO.kt:13](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/dto/request/PostRequestDTO.kt:13>), [src/main/kotlin/com/studentstorefront/dto/update/PostUpdateDTO.kt:8](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/dto/update/PostUpdateDTO.kt:8>), [build.gradle.kts:82](</home/zach/Desktop/productivity/Programming/SDE Sales App/build.gradle.kts:82>).

The running app accepted `imageUrlList: ["not-a-url"]` and stored it, despite type-use `@URL`/`@NotBlank` annotations in source. The build does not explicitly enable JVM type-annotation emission. Inspection with `javap -v` found the URL annotation in Kotlin metadata but no JVM type-annotation sections in the compiled DTO. Fix annotation emission or use validated nested objects, then verify the behavior through HTTP rather than trusting source annotations alone. Update title uses only `@Size(max=100)` and accepted an empty string. Creation requires nonblank text, so the same listing can become invalid through update.

Price checks impose a numeric range but no scale. Creating price `12.345` returned that exact value, while the stored/reloaded numeric(10,2) value was `12.35`. Clients see success data that does not match persistence. Image limits also differ: JSON update caps image count at five, JSON create has no collection cap, and multipart flows have no equivalent count rule.

**Fix:** apply validated nested media DTOs or ensure emitted container annotations work; restrict approved URL schemes/hosts as S06 requires. Use explicit optional-nonblank update validation, `@Digits`/canonical money rounding before persistence, and one image count/content rule across paths. Add service-level domain validation where non-HTTP callers bypass DTO validation.

**Acceptance:** invalid URLs, blank titles, and over-limit collections are rejected consistently; create responses and subsequent reads return identical monetary values.

## D07 — Session/token retention and expiry reporting are incomplete

**Severity: Medium. Confidence: source-confirmed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:44](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:44>), [src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:127](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/WhatsAppQrLoginService.kt:127>), [src/main/kotlin/com/studentstorefront/repository/WhatsAppLoginSessionRepository.kt:18](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/repository/WhatsAppLoginSessionRepository.kt:18>), [src/main/kotlin/com/studentstorefront/service/PasswordResetService.kt:58](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PasswordResetService.kt:58>).

QR cleanup only changes stale PENDING rows to EXPIRED every five minutes. It never deletes rows or expires stale COMPLETED rows. Polling returns stored status and claim token without checking deadlines, so it can say PENDING after the confirmation deadline or COMPLETED after claim has become impossible. The actual claim/confirm methods check time, so stale polling is not itself an expiry bypass.

Reset cleanup runs globally only on successful password reset. Verification tokens have no expiry purge job. Abandoned registrations, expired sessions, consumed credentials, and phone/IP metadata can accumulate indefinitely. Bot JSON state and Cloudinary objects have independent retention, outside account deletion.

**Fix:** define bounded retention by record class, derive effective polling state from deadlines, and schedule indexed batch cleanup. Purge or hash no-longer-needed bearer secrets promptly. Include bot/asset data in the account lifecycle and audit the intended retention purpose.

**Acceptance:** expired credentials cannot be used or advertised as claimable; a test clock advances all state transitions deterministically; old records are purged in bounded batches independent of user traffic.

## D08 — Media replacement loses identity and has inconsistent cover/removal behavior

**Severity: Medium. Confidence: source-confirmed.**

**Evidence:** [src/main/kotlin/com/studentstorefront/service/PostService.kt:54](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:54>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:281](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:281>), [src/main/kotlin/com/studentstorefront/service/PostService.kt:316](</home/zach/Desktop/productivity/Programming/SDE Sales App/src/main/kotlin/com/studentstorefront/service/PostService.kt:316>).

Create-upload does not validate coverIndex; an out-of-range value stores no cover. Failed individual uploads are silently skipped if at least one succeeds, potentially removing the chosen cover. Update instead moves the chosen cover to position zero. Both JSON and multipart replacement delete/recreate media, losing any historical image_hash and stable media IDs. An empty imageOrder means “keep existing plus uploads,” so it does not mean “remove all.” Uploads can succeed before an invalid reference/order causes rollback.

**Fix:** define a single ordered-media representation and whether zero images are valid. Validate all references/count/cover rules before uploads, preserve retained media identities/hashes, and make partial upload handling explicit. Enforce at-most-one cover and valid order in the database if retaining both fields.

**Acceptance:** zero/negative/out-of-range cover cases are deterministic; retained images retain identity/hash; bad ordering does not orphan uploads; removal semantics are explicit and tested.
