# Query Results For The Seeded Dataset

The tables below show the expected output when `schema.sql`, `seed.sql`, and `queries.sql` are executed together.

## Query 1 Result

Question: which active furniture listings match a search for `desk`?

| post_id | title | category | price | seller_name | created_at |
| --- | --- | --- | ---: | --- | --- |
| 3 | Desk Lamp | FURNITURE | 18.00 | Carla Mendes | 2026-05-14 08:45:00 |
| 14 | Dorm Storage Shelves | FURNITURE | 30.00 | Emma Fischer | 2026-05-14 07:55:00 |

## Query 2 Result

Question: which listings need an expiration reminder if the scheduler runs at `2026-05-14 12:00:00`?

| post_id | title | expires_at | phone_number |
| --- | --- | --- | --- |
| 3 | Desk Lamp | 2026-05-14 12:30:00 | +491111111103 |
| 12 | Bike Helmet | 2026-05-14 12:45:00 | +491111111103 |

## Query 3 Result

Question: what is seller `1`'s review summary as both seller and buyer?

| reviewee_id | direction | average_rating | review_count |
| --- | --- | ---: | ---: |
| 1 | BUYER_TO_SELLER | 5.0 | 1 |
| 1 | SELLER_TO_BUYER | 4.0 | 1 |
