# Database Audit — 2026-09-22 (current codebase)

In-depth evaluation of the PostgreSQL database, its Flyway/JPA schema, every query
path in the application, and the `docs/database/` course deliverables.

> **Supersedes** `../database-audit/` and `../database-audit-2026-05-14.md`, which
> were written before the integrity/security hardening rework (V3–V7, triggers,
> outbox, buyer confirmation, pessimistic locks, `MediaUrlPolicy`,
> `SecretsConfiguration`). Those older findings are mostly fixed; see
> [08-superseded-findings.md](08-superseded-findings.md) for what changed.

**Scope reviewed:** `docs/REPOMAP.md`, `docs/database/*` (schema.sql, schema.dbml,
seed.sql, queries.sql), `src/main/resources/db/migration/V1–V7`, all JPA entities
and repositories, all services/controllers that read or write the database, the
raw JDBC paths (`RateLimitFilter`, `CredentialCleanupScheduler`), DTO validation,
and `GlobalExceptionHandler`. The WhatsApp bot (`bot/`) has **no** database access
(it only calls the REST API), and the static frontend contains no
`innerHTML`/`x-html`-style sinks, so DB-sourced strings are not directly
script-injectable today.

## Report index

| File | Contents |
| --- | --- |
| [01-how-the-database-works.md](01-how-the-database-works.md) | Architecture, tables, flows, triggers, index strategy |
| [02-schema-and-migration-findings.md](02-schema-and-migration-findings.md) | Schema/migration/seed/DBML defects and drift (`F-*`) |
| [03-security-vulnerabilities.md](03-security-vulnerabilities.md) | Vulnerabilities and abuse paths (`V-*`) |
| [04-injection-points.md](04-injection-points.md) | SQL/LIKE/order-by/XSS analysis of every query path (`I-*`) |
| [05-dead-code-and-fields.md](05-dead-code-and-fields.md) | Unused fields, tables, methods, duplicate code (`D-*`) |
| [06-quality-and-consistency.md](06-quality-and-consistency.md) | Program quality and consistency issues (`Q-*`) |
| [07-performance-and-indexing.md](07-performance-and-indexing.md) | Missing indexes, N+1 access, query problems (`P-*`) |
| [08-superseded-findings.md](08-superseded-findings.md) | Prior audit findings and how the rework resolved them |

## Executive summary

The database design is genuinely strong for a student project: a normalized core
(`users/posts/post_media/sales/reviews/favourites`), immutable sale snapshots
(`title_snapshot`/`price_snapshot`), partial indexes matched to the dominant query
paths, optimistic locking (`@Version`) on `users`/`posts`, pessimistic locks on all
credential-consuming lookups, a transactional outbox for notifications, and —
unusually — **three plpgsql triggers plus named CHECK constraints** enforcing
sale/review participant integrity at the storage layer. I found **no SQL
injection**: every native query uses bound parameters and every `LIKE`/`ORDER BY`
path is escaped or whitelisted.

The remaining problems concentrate at the edges:

1. **The seed file does not load** against the canonical schema — a PENDING
   WhatsApp session row violates `chk_whatsapp_pending_fields` (F-2, High).
2. The rate limiter can be **bypassed by spoofing `X-Forwarded-For`** (leftmost-hop
   parsing), re-enabling brute force of 6-digit verification codes (V-1, High).
3. `JwtService` **silently falls back to a random signing key** when `jwt.secret`
   is blank (V-2, High).
4. `notification_outbox.payload` stores **plaintext verification codes and reset
   links**, only purged ≥1h after successful delivery (V-3, Medium).
5. `schema.sql`, the Flyway migrations, and `schema.dbml` have **drifted** —
   constraint names, a missing `chk_posts_version`, and a wrong
   `uk_users_pending_email` shape in DBML (F-1/F-4).
6. **Dead weight**: `creator_ip` is write-only, the two `migration_*_quarantine`
   tables ship in the clean-install schema with zero code references, several
   repository methods have no callers, and `matchesBotKey`/`escapeLike`/
   `safePageable`/`normalizePhone` are each duplicated 2–3× (D/Q files).
7. Review/sale listing endpoints have **N+1 lazy loading** (P-2) and one lookup
   (`email_verification_tokens.target_email`) has **no index** (P-1).

## Findings by severity

| Severity | IDs |
| --- | --- |
| High | F-2, V-1, V-2 |
| Medium | F-1, F-3, V-3, V-4, V-5, V-6, Q-3, Q-7 |
| Low | F-4…F-11, V-7…V-10, D-*, Q-1, Q-2, Q-4…Q-6, P-1, P-5…P-7 |
| Info/quality | V-11, V-12, I-*, Q-8…Q-14, P-2…P-4, P-8…P-12 |

ID prefixes: `F-*` schema/migration, `V-*` security, `I-*` injection, `D-*` dead
code, `Q-*` quality/consistency, `P-*` performance.
