-- Query 1
-- Business question:
-- Which active furniture listings match a student searching for "desk"?
SELECT
    p.post_id,
    p.title,
    p.category,
    p.price,
    u.name AS seller_name,
    p.created_at
FROM posts p
JOIN users u ON u.user_id = p.seller_user_id
WHERE p.is_sold = FALSE
  AND p.status = 'ACTIVE'
  AND p.category = 'FURNITURE'
  AND (
      LOWER(p.title) LIKE LOWER('%desk%')
      OR LOWER(p.description) LIKE LOWER('%desk%')
  )
ORDER BY p.created_at DESC, p.post_id DESC;

-- Query 2
-- Business question:
-- If the expiration scheduler runs at 2026-05-14 12:00:00, which active,
-- unsold listings need a reminder within the next hour?
SELECT
    p.post_id,
    p.title,
    p.expires_at,
    u.phone_number
FROM posts p
JOIN users u ON u.user_id = p.seller_user_id
WHERE p.status = 'ACTIVE'
  AND p.is_sold = FALSE
  AND p.reminder_sent_at IS NULL
  AND p.expires_at IS NOT NULL
  AND p.expires_at > TIMESTAMP '2026-05-14 12:00:00'
  AND p.expires_at <= TIMESTAMP '2026-05-14 13:00:00'
ORDER BY p.expires_at, p.post_id;

-- Query 3
-- Business question:
-- What is Alice Johnson's rating summary as a seller and as a buyer?
SELECT
    r.reviewee_user_id,
    r.direction,
    ROUND(AVG(r.rating)::numeric, 1) AS average_rating,
    COUNT(*) AS review_count
FROM reviews r
WHERE r.reviewee_user_id = 1
GROUP BY r.reviewee_user_id, r.direction
ORDER BY r.direction;
