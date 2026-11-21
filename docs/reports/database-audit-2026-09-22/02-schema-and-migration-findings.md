# Schema, migration, seed and DBML findings (`F-*`)

[Report index](README.md)

Each item: what the part is, exactly how it is wrong or unoptimal, and the fix.

---

## F-2 — `seed.sql` violates `chk_whatsapp_pending_fields` and cannot load (High)

**Where:** `docs/database/seed.sql:114` vs `docs/database/schema.sql:81-84`.

**What's wrong:** the first seeded `whatsapp_login_sessions` row is
`('…1111', '…1111', '…1111', NULL, 'PENDING', NULL, '+491111111102', …)` — status
`PENDING` with `phone_number` set. The schema's check requires
`status <> 'PENDING' OR (user_id IS NULL AND phone_number IS NULL AND claim_token IS NULL
AND completed_at IS NULL AND claimed_at IS NULL)`, so this `INSERT` raises
`chk_whatsapp_pending_fields` and the whole `BEGIN…COMMIT` seed block aborts.

**Why it's wrong:** `seed.sql` is a graded deliverable ("deterministic fake
dataset") and `query-results.md` depends on it. As committed, a clean
`schema.sql + seed.sql` install fails — the demo cannot run and the expected query
results cannot be reproduced.

**Fix:** set `phone_number` to `NULL` on the PENDING row (the phone is only known
after bot confirmation, which is what the check encodes). Add a CI step that loads
`schema.sql + seed.sql` into a scratch PostgreSQL and runs `queries.sql` against
`query-results.md`.

---

## F-1 — `schema.sql`, Flyway V2/V3 and the entity layer have drifted (Medium)

**Where:** `docs/database/schema.sql` vs `V2__user_sales_schema_cleanup.sql` /
`V3__security_integrity_hardening.sql`.

**What's wrong — per drift:**

- Constraint naming: migrations create named constraints (`chk_users_role`,
  `chk_posts_price`, `chk_posts_description`, `chk_post_media_order`,
  `chk_email_verification_attempts`, …) while `schema.sql` inlines the same rules
  **unnamed** (`price NUMERIC(10,2) NOT NULL CHECK (price >= 0.01 …)`). Error
  messages and `DROP CONSTRAINT` tooling therefore behave differently between a
  docs-install and a Flyway-install.
- `chk_posts_version CHECK (version >= 0)` exists only in V3 — `schema.sql` has no
  equivalent (it only has `chk_users_version`). A docs-install accepts negative
  `posts.version`.
- `uk_users_pending_email` is created inside `schema.sql` mid-file (before
  `email_verification_tokens`), but as the last statement of V7 for upgrades —
  harmless but shows the two files were edited independently.

**Why it's wrong:** `schema.sql`'s header claims it is "the clean-install
reference" and REPOMAP claims Flyway "keeps the production schema in sync with the
JPA model". With three hand-maintained sources of truth (V2+V3+V7, `schema.sql`,
`schema.dbml`) plus the JPA annotations, they will keep diverging — and
`ddl-auto=validate` only validates columns/types, never constraints or indexes, so
nothing at startup detects the drift.

**Fix:** generate `schema.sql`/`schema.dbml` from a Flyway-migrated scratch
database (e.g., `pg_dump --schema-only` + a DBML exporter) in CI and diff them.
Give every constraint the same name in both paths.

---

## F-3 — `ON DELETE SET NULL` contradicts `chk_whatsapp_completed_fields` (Medium)

**Where:** `docs/database/schema.sql:74` and `:85-88` (same in V2/V3).

**What's wrong:** `whatsapp_login_sessions.user_id` is
`REFERENCES users(user_id) ON DELETE SET NULL`, but the state check demands
`user_id IS NOT NULL` for `COMPLETED`/`CLAIMED` rows. Deleting a user who has a
completed or claimed session via plain SQL makes the FK null the column, which
immediately violates the CHECK — the `DELETE` fails with a check violation instead
of doing what the FK promises. The two constraints encode incompatible policies.

**Why it's wrong:** the app currently sidesteps this only because
`UserService.clearUserDependencies`/`clearUserCredentials` delete session rows
first. Any other writer (DBA cleanup, future code, `ON DELETE` from another table)
hits a confusing "check constraint" error on a table it isn't touching.

**Fix:** pick one policy: `ON DELETE CASCADE` (sessions are per-user artifacts), or
keep `SET NULL` and relax the check to allow `user_id IS NULL` on terminal states
(retaining `phone_number` as the audit key). Document the choice.

---

## F-4 — `schema.dbml` misrepresents two constraints (Low)

**Where:** `docs/database/schema.dbml` (`users` indexes; `api_rate_limit_windows`).

**What's wrong:**

- `(pending_email) [unique, name: 'uk_users_pending_email']` — the real constraint
  is unique on **`lower(pending_email)` and partial (`WHERE pending_email IS NOT
  NULL`)**. Anyone regenerating a schema from the DBML (dbdiagram.io export) gets a
  different, stricter constraint (NULL-collision behavior and case handling differ).
- `request_count integer [not null, default: 0]` — `schema.sql` defines **no
  default**; inserts must supply it (the app does).
- FK delete behaviors (`post_media`/`favourites` `ON DELETE CASCADE`,
  `whatsapp_login_sessions` `ON DELETE SET NULL`, `sales.post_id` cascade) are not
  expressed at all, so the DBML under-describes destructive semantics.
- The three triggers and all `chk_*` rules are absent (DBML can't express them) —
  fine, but the file should say so; a reader trusting the diagram misses the most
  important integrity logic in this schema.

**Fix:** annotate the DBML with the real index expressions and a comment block
listing triggers/CHECKs/FK actions, or generate it (F-1).

---

## F-5 — Migration quarantine tables ship in the clean-install schema (Low)

**Where:** `docs/database/schema.sql:267-291`, `schema.dbml`, created by V2.

**What's wrong:** `migration_sale_quarantine` / `migration_review_quarantine` are
V2's audit trail for quarantined legacy rows. They have **zero references** anywhere
in `src/` or `bot/` (grep-confirmed), yet they are part of the canonical
"clean-install reference" and the ER documentation. On a fresh install they are
always empty and always will be.

**Why it's wrong:** dead tables in a graded schema invite questions ("where is the
API for these?"), add noise to the diagram, and suggest features that don't exist.

**Fix:** keep the tables in V2 (that is where the audit value is) but drop them
from `schema.sql`/`schema.dbml`, with a one-line note that they are
migration-only artifacts. If they are meant to be permanent, add a documented
reconciliation procedure.

---

## F-6 — Notification type enum is a two-fold hand-synced source of truth (Low)

**Where:** `enums/NotificationType.kt` vs `notification_outbox_type_check` (V5,
recreated in V7).

**What's wrong:** the Kotlin enum and the SQL CHECK list must be edited in lockstep.
They already drifted once — V5's list lacked `BOT_ACCOUNT_DELETED`, requiring V7 to
`DROP CONSTRAINT`/re-`ADD` it (V7's comment admits the sequencing problem). A third
notification type will need V8 doing the same dance, and any mismatch surfaces as a
runtime `DataIntegrityViolationException` on enqueue.

**Fix:** either drop the CHECK (the enum is already the write-side gate and the
outbox is internal) or generate the CHECK from the enum. If the CHECK stays, keep a
test asserting enum values == constraint values (query `pg_constraint`).

---

## F-7 — Near-duplicate `post_media` index (Low)

**Where:** `schema.sql:296-297` + `:313`; V2/V3 equivalents.

**What's wrong:** `uk_post_media_post_order UNIQUE (post_id, display_order)` and
`idx_post_media_post_order (post_id, display_order, id)` are almost identical. The
unique index already serves `ORDER BY display_order` and any `(post_id, …)` lookup;
the extra index only adds `id` as a tiebreaker column, which `findOrderedPostMedia`
doesn't even use at the SQL level (it sorts in memory) and the ordered derived query
(`…OrderByPostPostIdAscIsCoverDescDisplayOrderAscIdAsc`) can satisfy from the unique
index with a trivial sort.

**Why it's wrong:** every extra index slows all `post_media` writes (which happen on
every listing create/update) and doubles the storage for the same access path.

**Fix:** drop `idx_post_media_post_order`; keep the unique constraint.

---

## F-8 — Timestamp types mix three writing conventions (Low)

**Where:** all `TIMESTAMP` (without time zone) columns; `RateLimitFilter.currentWindowStart`/
`consumeSharedBudget`; `CredentialCleanupScheduler.cleanExpiredCredentials`;
entity `LocalDateTime.now(ZoneOffset.UTC)` defaults; `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`.

**What's wrong:** entities and Hibernate write **UTC wall time** into naive
`TIMESTAMP` columns (correct under `time_zone=UTC`), but the two JDBC paths build
`java.sql.Timestamp.valueOf(utcLocalDateTime)`. `Timestamp.valueOf` interprets its
argument as **JVM-default-zone** wall time; the driver then re-renders it using the
JVM zone. The two conversions currently cancel out, but only as an accident of
implementation: any change to driver/timezone handling, a `TIMESTAMPTZ` migration,
or running the scheduler in a DST-transition window can skew `window_start`/cleanup
cutoffs by the host offset.

**Why it's wrong:** one schema, three date idioms, zero shared helper — the classic
"works on my machine" timezone trap.

**Fix:** migrate to `TIMESTAMPTZ` end-to-end (or enforce "always UTC naive" with one
`fun utcNow(): LocalDateTime` + `Timestamp.valueOf` helper used everywhere), and
never call `Timestamp.valueOf` on a UTC `LocalDateTime` without a comment.

---

## F-9 — `uk_users_pending_email` is case-insensitive but the lookup is case-sensitive (Low)

**Where:** `schema.sql:103-105` (unique on `lower(pending_email)`) vs
`UserRepository.findByPendingEmail(pendingEmail: String)` (exact match).

**What's wrong:** the constraint treats `A@constructor.university` and
`a@constructor.university` as the same pending email; the query does not. Today
`IdentityNormalizer.email` lowercases before both writes and reads so behavior is
correct, but the invariant lives in the service, not in the repository contract — a
future caller of `findByPendingEmail` with raw input silently misses rows and can
attempt a `pendingEmail` write that the DB then rejects.

**Fix:** query `WHERE lower(pendingEmail) = lower(:pendingEmail)` (or document and
assert the normalization precondition on the repository method).

---

## F-10 — Migrations drop *all* CHECK constraints on `users`/`posts` (Low)

**Where:** V2 (`FOR constraint_name IN … contype = 'c' … DROP CONSTRAINT` blocks for
`users`, `posts`, `post_media`) and the same loop in V3.

**What's wrong:** the loop drops every check constraint on the table regardless of
name or origin — including constraints this project didn't create (DBA-added
guards, future extensions, other apps sharing the schema). V2 does it before
re-adding its own; V3 does it *again*.

**Why it's wrong:** a migration should be additive/explicit about its own objects.
Silently deleting unknown constraints is destructive and non-reproducible: the same
migration yields different final schemas depending on what was installed before.

**Fix:** drop only the constraint names this project owns (they are all named
`chk_*` after V2 — one more reason for F-1's naming unification).

---

## F-11 — Trigger correctness depends on Hibernate flush ordering (Info/Low)

**Where:** `PostService.markAsSold` (post `save()` then `saleRepository.save()`)
vs `enforce_sale_participants`, which reads `posts.is_sold` at sale-INSERT time.

**What's wrong:** the post's `is_sold=TRUE` update and the sale insert are two
statements flushed in one transaction. Hibernate orders flush actions by type
(inserts vs updates) rather than by call order, so the trigger's view of `posts`
depends on which statement hits the wire first. The end-to-end test
(`ReviewWorkflowControllerTest` mark-sold via Testcontainers) passes today, so the
current combination is fine — but the invariant is enforced by a *trigger* while the
write order is chosen by an *ORM version*, which is exactly the kind of coupling
that breaks after a Hibernate upgrade.

**Fix:** make the checks `DEFERRABLE INITIALLY DEFERRED`, or flush the post update
explicitly (`entityManager.flush()`) before saving the `Sale`, and add a test that
reproduces concurrent mark-sold (unique `sales.post_id` already gives the
second-writer a clean 409 via `DataIntegrityViolationException`).
