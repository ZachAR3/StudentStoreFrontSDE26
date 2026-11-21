# Database and persistence audit

**Reviewed:** 9–10 September 2026. **Repository HEAD:** `1726e6d3c0809e8e7ee16a1cedc15be96dd3c15a`. This report evaluates the working tree, including pre-existing uncommitted changes, rather than HEAD alone. Entry point: [docs/REPOMAP.md:1](</home/zach/Desktop/productivity/Programming/SDE Sales App/docs/REPOMAP.md:1>). The remediation is implemented in the current working tree; the findings below remain the baseline evidence and acceptance checklist.

The baseline reviewed by this report had serious defects in authentication, migration safety, and consistency across the database, backend, and bot. The current working tree contains the corresponding remediation; the findings below remain the baseline evidence and acceptance checklist. Production release still depends on the deployment secrets/privileges being configured and the Docker-backed integration suite passing in CI.

## Read the report

1. [Architecture, schema, and field inventory](01-architecture-and-field-inventory.md): how the runtime tables work; which fields are active, redundant, dormant, or missing safeguards.
2. [Security and injection findings](02-security-and-injection.md): authentication, authorization, stored URLs, upload paths, rate limits, and information exposure.
3. [Migration and data-integrity findings](03-migrations-and-integrity.md): failing upgrades, lost data, deletion behavior, races, and transaction boundaries.
4. [Performance, dead code, and consistency](04-performance-and-code-quality.md): query costs, indexes, broken bot integration, serialization, and lifecycle inconsistencies.
5. [Evidence and remediation plan](05-evidence-and-remediation.md): exact observed results, limits of testing, safe reproduction instructions, and acceptance criteria.

## Highest-priority results

| Finding | Severity | Result |
|---|---|---|
| S01 | Critical when fallback configuration is used | A repository-known JWT signing secret and bot key are accepted defaults; the bot key authorizes login confirmation for an asserted phone number. |
| S02 | High | Disabled accounts still pass JWT authentication. Reusing an email lets an old token authenticate as the new account. Both reproduced over HTTP. |
| S03 | High | A verified account can change email without verification or recent authentication, retaining its enabled status. Reproduced. |
| S05 | High | One WhatsApp claim token produced six HTTP 200 responses from eight concurrent requests. |
| S06 | High | Stored listing URLs reach an unrestricted server-side image fetch. The installed fetch helper accepted a loopback URL and returned arbitrary text. The bot startup defect currently blocks the normal full chain. |
| S07 | High | Client-supplied upload filenames form filesystem paths, permitting traversal and collisions in the helper. Source-confirmed; no sensitive paths were touched. |
| M01–M03 | High | Legacy upgrades fail on `SOLD`/`USER` constraints; migrated review sequences collide; unsupported reviews can disappear during conversion. |
| D01 | High | Account deletion leaves `sales.buyer_user_id` references and fails. Reproduced in PostgreSQL. |
| C01–C02 | High | Bot startup fails on an undefined export; fixing that alone leaves phone lookup blocked by the backend security filter. |
| C03 | High | The live API sends `sold`; the frontend reads `isSold`. A sold record was rendered by the adapter as unsold. |

Severity reflects impact, exposure, and prerequisites. **Critical conditional** does not mean the deployed system is proven to use the defaults. **High** covers account compromise, exploitable writes/reads, data loss, or broken core workflows. **Medium** covers integrity weaknesses, abuse amplification, and substantial maintainability or performance defects. **Low** covers cleanup candidates and minor design issues.

Findings distinguish **reproduced**, **source-confirmed**, and **conditional** behavior. A directly inserted invalid database row demonstrates a missing database safeguard; it does not prove a public API accepts that row. Likewise, a local sink test does not establish access to any real internal service.

## Scope and validation

Reviewed all JPA entities and repositories; Flyway migrations V1–V7; database SQL/DBML/seed/query documentation; controllers, security filters, DTOs, services, schedulers; relevant frontend adapters and templates; bot persistence, submission, lookup, login, media handling, and tests/CI. GitNexus was indexed for the current repository and used alongside source searches and direct execution.

This report contains **34 numbered findings**, plus a complete field inventory and remediation criteria. The current implementation adds the corresponding security, migration, lifecycle, media, outbox, bot-contract, buyer-confirmation, and documentation changes; the validation results below distinguish environment-limited checks from code failures.

Validation included **46 passing existing unit tests**, a disposable **PostgreSQL 18.6** cluster, a fresh Spring Boot startup using the actual Flyway migrations, local HTTP probes, concurrent claim requests, and isolated JavaScript module/media/adapter probes. Testcontainers-based integration suites were not run because the Docker socket was inaccessible. Their configured PostgreSQL version is 15; the native database probes used 18.6. No production database, real messages, real accounts, or external media service was used. Temporary audit servers were stopped.

**SQL injection:** no executable SQL/JPQL injection was found in the reviewed application paths. Search uses bound parameters and escapes `%`, `_`, and `!`. The significant injection-related problems are stored URL fetching and filesystem path construction. See the complete sink matrix in the security report.
