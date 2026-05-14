# Demo And Deployment Notes

## Current Status

- Local MVP: implemented in this repository
- Database-backed create/read/update flows: implemented
- Recorded demo: not produced inside the repo, but the script below is ready to follow
- Cloud deployment link: not available from repository-only work; add the final URL after deployment

## Suggested Demo Script

Record a short 2 to 4 minute video showing one read path and one write path that visibly update the database.

If you load `docs/database/seed.sql`, every enabled seeded account uses the password `Password123!`.

1. Start PostgreSQL locally with `docker compose up -d db`.
2. Start the backend with `./gradlew bootRun`.
3. Open `http://localhost:8080`.
4. Log in with an existing verified account or register a fresh `@constructor.university` seller account.
5. Create a new listing with title, price, description, category, and image.
6. Refresh the marketplace to show the new listing is persisted.
7. Open the database and run:

```sql
SELECT post_id, title, seller_id, status, is_sold
FROM posts
ORDER BY post_id DESC
LIMIT 5;
```

8. Mark a listing as sold to a registered buyer.
9. Show that `buyer_id`, `is_sold`, and `sold_at` changed in the database.
10. Open the review flow and submit one review.
11. Run:

```sql
SELECT review_id, post_id, reviewer_id, reviewee_id, direction, rating
FROM reviews
ORDER BY review_id DESC
LIMIT 5;
```

12. End the recording by briefly showing Swagger UI or the repo documentation to demonstrate the full stack.

## Recommended Deployment Shape

The simplest deployment for grading is one hosted Spring Boot service plus one managed PostgreSQL database:

- Backend + static frontend: Render web service
- PostgreSQL: Render Postgres or Supabase Postgres
- Optional bot runtime: separate Node service only if you want to demo the WhatsApp integration

This keeps the grading path simple because the same Spring app serves the REST API and the browser frontend.

## Final Submission Fields To Fill In

- Deployed app URL: `TODO`
- Demo video URL: `TODO`
- Date of deployment: `TODO`
- Tested commit or tag: `TODO`
