# Performance and indexing (`P-*`)

[Report index](README.md)

Scale context: a campus marketplace — thousands of rows, dozens of writes/minute.
Items are ranked by "will bite first", not by theoretical purity.

---

## P-1 — `email_verification_tokens.target_email` lookup has no index (Low now, wrong-by-construction)

**Where:** `EmailVerificationTokenRepository.findTopByTargetEmailAndUsedFalseOrderByCreatedAtDesc`
(`WHERE target_email = ? AND used = false ORDER BY created_at DESC LIMIT 1`) vs
`idx_email_verification_user_state (user_id, used, created_at DESC)`.

**What's wrong:** the email-change verification path filters on `target_email`,
which appears in **no index** — every code verification for an email change is a
sequential scan over the whole token table (which also holds every registration
code). The existing index only covers the by-user lookup. Fine at demo scale, a
guaranteed slow spot the day the table holds a year of rows.

**Fix:** `CREATE INDEX idx_email_verification_target_state
ON email_verification_tokens(target_email, used, created_at DESC);`
(and mirror it in `schema.sql`/DBML per F-1).

## P-2 — N+1 lazy loading in the sale/review read paths (Low-Medium)

**Where:**
- `SaleRepository.findPendingConfirmationsForBuyer` / `findPendingReviewsForUser` —
  return `Sale` with lazy `post`, `seller`, `buyer`; `SaleService.getPendingConfirmations`
  and `ReviewService.getPendingReviewContexts` then touch `sale.post.postId`,
  `sale.post.title`, `sale.seller.name`, `sale.buyer` per row (up to `MAX_PENDING_* = 100` rows).
- `ReviewRepository.findByRevieweeUserIdOrderByCreatedAtDescReviewIdDesc` mapped in
  `ReviewService.getProfileReviews` — each `Review` touches `sale`, `sale.post`,
  `reviewer`, `reviewee` (5 rows default → ~20 selects).

**What's wrong:** `PostRepository` already solves this correctly
(`@EntityGraph(attributePaths = ["user", "buyer"])`, `JOIN FETCH` in the search/reminder
queries) and `PostService.mapPosts` batches media/favourites — but the sale/review
services predate that pattern. Worst case is ~300 statements for one "pending
reviews" poll, and the frontend polls this.

**Fix:** add `JOIN FETCH s.post LEFT JOIN FETCH s.seller LEFT JOIN FETCH s.buyer`
(or an `@EntityGraph`) to the two sale queries and
`JOIN FETCH r.sale LEFT JOIN FETCH r.sale.post …` to the review finder.

## P-3 — Repeated identical principal lookups per request (Low)

**Where:** `PostService.getCurrentUser()`, `getCurrentUserIdOrNull()`,
`isCurrentUserAdmin()` each call
`userRepository.findByEmail(IdentityNormalizer.email(auth.name))`; single-post
mapping (`mapToResponseDTO(post, currentUserId)`) triggers up to three of them plus
`findOrderedPostMedia` + `existsByUserUserIdAndPostPostId`, while `mapPosts` does
the batched thing.

**What's wrong:** one HTTP request can issue 3–5 identical `users` lookups (e.g.
`updatePost` → `saveUpdatedPost`(getCurrentUser) → map(isCurrentUserAdmin) →
map(getCurrentUserIdOrNull)), and the singular/batch mapping paths disagree about
how many queries a response costs (the bot's `createPostAsBot` response goes through
the singular path).

**Fix:** resolve the principal once per request (a `SecurityUtils` helper that
caches in request scope — ties into Q-2) and route single-post mapping through
`mapPosts(listOf(post), …)`.

## P-4 — Account-deletion cleanup is O(posts) round trips (Low)

**Where:** `UserService.clearUserDependencies` — `postRepository.findByUserUserId(userId)`
loads **all** owned posts (P-11), then loops:
`favouriteRepository.deleteByPostPostId(postId)` +
`reviewRepository.deleteBySalePostPostId(postId)` per post, plus
`mediaCleanupService.scheduleForPosts` re-fetching media per post set.

**What's wrong:** a seller with 200 listings turns one account deletion into 400+
individual DELETE/SELECT statements inside a single transaction (long locks on the
involved tables). Set-based deletes exist and are trivial here.

**Fix:** bulk `DELETE FROM favourites WHERE post_id IN (SELECT post_id FROM posts WHERE seller_user_id = :uid)`,
same for reviews via sale, one media fetch, then `DELETE FROM posts WHERE seller_user_id = :uid`
(already guarded by `hasSales`, so no trigger conflict — see D-15).

## P-5 — `notification_outbox` due-index shape is suboptimal (Low)

**Where:** `idx_notification_outbox_due (sent_at, next_attempt_at)` vs the poll query
in `findDueForUpdate`:
`WHERE sent_at IS NULL AND next_attempt_at <= :now AND (claimed_at IS NULL OR claimed_at < :claimBefore)
ORDER BY next_attempt_at, id`.

**What's wrong:** `sent_at IS NULL` is the dominant filter but sits in the middle of
the index's range scan semantics (NULLs sort first, so the planner can use the
prefix, but the index still contains every delivered row — the common case long-term
once `purgeDelivered` lags). The sort (`next_attempt_at, id`) isn't fully covered
either.

**Fix:** partial index `ON notification_outbox (next_attempt_at, id) WHERE sent_at IS NULL`
— tiny, matches the hot path exactly, and shrinks as rows are delivered.

## P-6 — `favourites` read sort has no supporting index (Low)

**Where:** `FavouriteService.getFavouritePosts` —
`findByUserUserId(userId, PageRequest.of(0, 100, sort: createdAt DESC, id DESC))`;
indexes are `uk_favourites_user_post (user_id, post_id)` and
`idx_favourites_post_id (post_id)`.

**What's wrong:** each user's favourites are fetched and sorted (up to 100 rows)
without an index that provides the order — a sort node per request. Also, the
collection is capped at a hardcoded `MAX_FAVOURITES = 100` with no DB/UX signal when
a user hits the cap (inserts beyond the page just never show up in the listing).

**Fix:** `(user_id, created_at DESC, id DESC)` index (can replace
`uk_favourites_user_post` if folded in carefully), and decide whether favorites are
capped or paginated.

## Q/P-7 — Pagination caps `size` but not `page` (Low)

**Where:** `PostService.safePageable` / `UserService.safePageable` —
`pageSize.coerceIn(1, MAX_PAGE_SIZE)` but `PageRequest.of(pageable.pageNumber, …)`.

**What's wrong:** `?page=100000` is accepted and materializes a `LIMIT 50 OFFSET
5000000` scan on the public feed endpoints (which are unauthenticated). Not
damaging at campus scale, but it's the cheapest unauthenticated DB-work amplifier
available (especially combined with `LIKE` search, P-8).

**Fix:** cap `pageNumber` or switch deep access to keyset pagination (`WHERE
(created_at, post_id) < (…)` — the existing `idx_posts_public_listing` already
matches that shape).

## P-8 — `LIKE '%q%'` search is inherently unindexable (Info)

**Where:** `PostRepository.searchAvailablePosts` —
`LOWER(title) LIKE LOWER(CONCAT('%', :query, '%'))` (same for description),
`UserRepository.searchEnabledUsersByName` on `LOWER(name)`.

**What's wrong:** not a bug — leading-wildcard `LOWER()` `LIKE` cannot use btree
indexes; every search is a seq scan over active posts. Acceptable at current size,
documented here so nobody is surprised later.

**Fix (when needed):** `pg_trgm` GIN indexes on `lower(title || ' ' || description)`
and `lower(name)`, or FTS (`tsvector`); alternatively restrict to prefix search and
index `lower(title) text_pattern_ops`.

## P-9 — `MediaUrlPolicy.validate` does blocking DNS per URL in the request path (Low)

**Where:** `service/MediaUrlPolicy.validate` — `InetAddress.getAllByName(host)` for
every media URL on every create/update.

**What's wrong:** each listing save can add several synchronous DNS resolutions
(outside app control: resolver latency, NXDOMAIN storms, slow authoritative DNS on
a hostile hostname a user supplied). A user batch-updating listings with
attacker-controlled hostnames can stretch request threads. Combined with P-7, this
is the main "slow request amplification" surface.

**Fix:** cache resolution results briefly (the check is anti-SSRF policy, not
freshness-critical), enforce a per-request URL cap (exists: `MAX_MEDIA_COUNT = 5` —
keep it), and/or move the fetch-side re-validation to the bot (I-5) and make the
write-side check advisory.

## P-10 — Two different media-ordering code paths (Low)

**Where:** `PostService.findOrderedPostMedia` — fetch all + in-memory
`sortedWith(compareBy({ !it.isCover }, { it.displayOrder }))` (note: ignores `id`
tiebreak) vs `mapPosts` using
`findByPostPostIdInOrderByPostPostIdAscIsCoverDescDisplayOrderAscIdAsc` (cover,
order, **id**).

**What's wrong:** single-post and list responses can disagree on image order for
rows with equal `displayOrder` (the unique `(post_id, display_order)` constraint
mostly prevents ties *within* a post, so latent), and the two orderings must be kept
in sync by hand. The in-memory path also transfers all columns of up to 5 rows —
fine — but the duplication is the real cost.

**Fix:** one query helper used by both paths (fold into `mapPosts`, per P-3).

## P-11 — Unbounded entity loads in lifecycle paths (Low)

**Where:** `PostRepository.findByUserUserId(userId): List<Post>` (full entity list,
used by `clearUserDependencies`), `PostMediaRepository.findByPost_postId` (fine),
`NotificationOutboxProcessor.claimDue` batch (bounded `MAX_BATCH = 50` — good).

**What's wrong:** the deletion path materializes every listing of a user (with
`@EntityGraph user, buyer`) just to extract ids and pass posts to
`MediaCleanupService`. Id projection (`SELECT p.postId FROM Post p WHERE …`) plus a
targeted media query would do.

**Fix:** id-only projections for cleanup paths (P-4).

## P-12 — What is already performant (Info)

Credit where due — don't "optimize" these away:

- Feed/search listing queries use **partial indexes** (`idx_posts_public_listing`,
  `idx_posts_public_category`) exactly matching `status = 'ACTIVE' AND is_sold = FALSE`.
- `mapPosts` batches media via one grouped query and favourites via one `IN` query
  — no N+1 on the hottest read path; `searchAvailablePosts` uses `JOIN FETCH`
  (to-one only, so pagination is not degraded by collection-fetch semantics).
- `archiveExpiredPosts` is a single set-based statement with `LIMIT 100` + covering
  `idx_posts_expiration_window`; reminder scanning uses the same partial index
  (including `reminder_sent_at IS NULL`).
- Rating summaries aggregate **in SQL** (`AVG`/`COUNT` projection
  `ReviewRepository.RatingAggregate`) rather than in Kotlin.
- Outbox claim is batched, leased (`claimed_at` + `PESSIMISTIC_WRITE`), and indexed;
  `ON CONFLICT DO NOTHING` inserts make favourites/outbox writes idempotent without
  read-then-write races.
- `hibernate.jdbc.time_zone=UTC`, `open-in-view=false`, and `ddl-auto=validate` are
  all the right defaults.
