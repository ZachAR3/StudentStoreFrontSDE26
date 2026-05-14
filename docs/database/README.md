# Database Course Deliverables

This folder collects the database-focused submission artifacts for the Student Storefront course project.

## Requirement Map

| Requirement | Status | Evidence |
| --- | --- | --- |
| Entities description | Complete | `domain-and-scenarios.md`, `docs/REPOMAP.md` |
| Critical scenarios / common user paths | Complete | `domain-and-scenarios.md` |
| Domain description strong enough to reconstruct schema | Complete | `domain-and-scenarios.md` |
| Database schema in SQL | Complete | `schema.sql` |
| Database schema in DBML | Complete | `schema.dbml` |
| Database schema image | Complete | `schema.svg`, `schema.png` |
| Fake valid data | Complete | `seed.sql` |
| 3 database queries with SQL | Complete | `queries.sql` |
| Indexes / performance optimizations | Complete | `schema.sql`, `domain-and-scenarios.md` |
| Working local MVP with database updates | Complete | `README.md`, existing app implementation |
| Recorded demo | Prepared, manual final step | `demo-and-deployment.md` |
| Deployed cloud link | Prepared, manual final step | `demo-and-deployment.md` |

## Files

- [`domain-and-scenarios.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/domain-and-scenarios.md): course-facing domain explanation and common user paths
- [`schema.sql`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/schema.sql): PostgreSQL DDL for the implemented data model
- [`schema.dbml`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/schema.dbml): DBML version of the same schema for dbdiagram.io
- [`schema.svg`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/schema.svg): schema diagram image
- [`schema.png`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/schema.png): PNG export of the schema diagram
- [`seed.sql`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/seed.sql): deterministic fake dataset
- [`queries.sql`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/queries.sql): three course-facing SQL queries
- [`query-results.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/query-results.md): example outputs for the seeded dataset
- [`demo-and-deployment.md`](/home/zach/Desktop/productivity/Programming/SDE Sales App/docs/database/demo-and-deployment.md): local MVP demo script and deployment checklist

## Notes

- The schema mirrors the implemented Kotlin/JPA entities in `src/main/kotlin/com/studentstorefront/entity`.
- The course brief suggests React and FastAPI, but the current Kotlin/Spring/PostgreSQL stack is already a working database-backed MVP and satisfies the actual database deliverables.
- The only items that cannot be completed purely by editing the repository are the external artifacts: a recorded demo and a live deployed URL.
