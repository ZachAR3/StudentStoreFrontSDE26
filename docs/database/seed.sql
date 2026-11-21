-- Deterministic demo data for docs/database/schema.sql.
-- Enabled accounts use the BCrypt hash for Password123!; token values are
-- already hashed examples and are not reusable raw credentials.

BEGIN;

INSERT INTO users (
    user_id, name, email, phone_number, password, role, is_enabled,
    registration_pending, token_version, phone_verified_at, created_at
) VALUES
    (1, 'Alice Johnson', 'alice.johnson@constructor.university', '+491111111101', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'USER', TRUE, FALSE, 0, '2026-05-01 09:01:00', '2026-05-01 09:00:00'),
    (2, 'Brian Lee', 'brian.lee@constructor.university', '+491111111102', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'USER', TRUE, FALSE, 0, '2026-05-01 09:06:00', '2026-05-01 09:05:00'),
    (3, 'Carla Mendes', 'carla.mendes@constructor.university', '+491111111103', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'USER', TRUE, FALSE, 0, '2026-05-01 09:11:00', '2026-05-01 09:10:00'),
    (4, 'Diego Novak', 'diego.novak@constructor.university', '+491111111104', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'USER', TRUE, FALSE, 0, '2026-05-01 09:16:00', '2026-05-01 09:15:00'),
    (5, 'Emma Fischer', 'emma.fischer@constructor.university', '+491111111105', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'USER', TRUE, FALSE, 0, '2026-05-01 09:21:00', '2026-05-01 09:20:00'),
    (6, 'Farah Khan', 'farah.khan@constructor.university', '+491111111106', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'USER', TRUE, FALSE, 0, '2026-05-01 09:26:00', '2026-05-01 09:25:00'),
    (7, 'Gregor Ivanov', 'gregor.ivanov@constructor.university', '+491111111107', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'USER', TRUE, FALSE, 0, '2026-05-01 09:31:00', '2026-05-01 09:30:00'),
    (8, 'Hana Petrov', 'hana.petrov@constructor.university', '+491111111108', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'USER', TRUE, FALSE, 0, '2026-05-01 09:36:00', '2026-05-01 09:35:00'),
    (9, 'Ivan Schmidt', 'ivan.schmidt@constructor.university', '+491111111109', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'USER', FALSE, TRUE, 0, NULL, '2026-05-14 11:55:00'),
    (10, 'Admin User', 'admin@constructor.university', '+491111111110', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'ADMIN', TRUE, FALSE, 0, '2026-05-01 09:41:00', '2026-05-01 09:40:00')
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO posts (
    post_id, title, price, description, category, is_sold, version, idempotency_key,
    created_at, expires_at, status, reminder_sent_at, sold_at, seller_user_id, buyer_user_id
) VALUES
    (1, 'TI-84 Plus Calculator', 65.00, 'Graphing calculator in good condition, ideal for calculus and statistics classes.', 'ELECTRONICS', FALSE, 0, NULL, '2026-05-13 09:00:00', '2026-05-15 09:00:00', 'ACTIVE', NULL, NULL, 1, NULL),
    (2, 'Algorithms Textbook', 28.00, 'Introduction to Algorithms third edition with light highlighting in chapters 1 to 6.', 'BOOKS', FALSE, 0, NULL, '2026-05-12 17:30:00', '2026-05-15 17:30:00', 'ACTIVE', NULL, NULL, 2, NULL),
    (3, 'Desk Lamp', 18.00, 'LED desk lamp with adjustable neck, perfect for late-night study sessions.', 'FURNITURE', FALSE, 0, NULL, '2026-05-14 08:45:00', '2026-05-14 12:30:00', 'ACTIVE', NULL, NULL, 3, NULL),
    (4, 'Mini Fridge', 110.00, 'Compact dorm fridge with freezer compartment and clean interior.', 'ELECTRONICS', FALSE, 0, NULL, '2026-05-11 11:00:00', '2026-05-14 11:30:00', 'ARCHIVED', '2026-05-14 10:35:00', NULL, 4, NULL),
    (5, 'Office Chair', 45.00, 'Black swivel chair with adjustable height and minor wear on the armrests.', 'FURNITURE', TRUE, 0, NULL, '2026-05-08 14:00:00', '2026-05-16 14:00:00', 'SOLD', NULL, '2026-05-12 15:00:00', 1, 5),
    (6, 'Linear Algebra Notes', 15.00, 'Printed and organized lecture notes for Linear Algebra, including solved exercises.', 'BOOKS', TRUE, 0, NULL, '2026-05-09 12:00:00', '2026-05-16 12:00:00', 'SOLD', NULL, '2026-05-10 14:00:00', 6, 2),
    (7, 'Bluetooth Headphones', 52.00, 'Over-ear Bluetooth headphones with charger cable and working noise cancellation.', 'ELECTRONICS', TRUE, 0, NULL, '2026-05-10 18:00:00', '2026-05-17 18:00:00', 'SOLD', NULL, '2026-05-11 18:30:00', 7, 3),
    (8, 'Football Boots', 35.00, 'Nike football boots size 43, used for one season but still in strong condition.', 'SPORTS', FALSE, 0, NULL, '2026-05-13 19:30:00', '2026-05-16 19:30:00', 'ACTIVE', NULL, NULL, 8, NULL),
    (9, 'Winter Jacket', 40.00, 'Warm winter jacket, size M, recently washed and ready for pickup.', 'CLOTHING', FALSE, 0, NULL, '2026-05-05 09:15:00', '2026-05-11 09:15:00', 'ARCHIVED', '2026-05-11 08:20:00', NULL, 5, NULL),
    (10, 'Graphic Design Tutoring', 20.00, 'One-hour tutoring sessions for Photoshop and Illustrator assignments.', 'SERVICES', FALSE, 0, NULL, '2026-05-14 09:30:00', '2026-05-16 09:30:00', 'ACTIVE', NULL, NULL, 4, NULL),
    (11, 'Chemistry Lab Coat', 12.00, 'Clean lab coat, size L, suitable for introductory chemistry labs.', 'CLOTHING', FALSE, 0, NULL, '2026-05-13 13:20:00', '2026-05-15 13:20:00', 'ACTIVE', NULL, NULL, 2, NULL),
    (12, 'Bike Helmet', 22.00, 'Adjustable bike helmet with removable visor and intact interior padding.', 'SPORTS', FALSE, 0, NULL, '2026-05-14 10:10:00', '2026-05-14 12:45:00', 'ACTIVE', NULL, NULL, 3, NULL),
    (13, 'Macroeconomics Workbook', 17.00, 'Workbook with practice questions and answer key for first-year macroeconomics.', 'BOOKS', FALSE, 0, NULL, '2026-05-12 08:50:00', '2026-05-15 08:50:00', 'ACTIVE', NULL, NULL, 6, NULL),
    (14, 'Dorm Storage Shelves', 30.00, 'Two-tier storage shelves that fit beside a dorm desk or under a lofted bed.', 'FURNITURE', FALSE, 0, NULL, '2026-05-14 07:55:00', '2026-05-15 07:55:00', 'ACTIVE', NULL, NULL, 5, NULL),
    (15, 'Gaming Monitor 24 Inch', 95.00, '24-inch full HD monitor with HDMI cable included and no dead pixels.', 'ELECTRONICS', FALSE, 0, NULL, '2026-05-13 21:10:00', '2026-05-15 21:10:00', 'ACTIVE', NULL, NULL, 1, NULL),
    (16, 'Acoustic Guitar Lessons', 25.00, 'Beginner guitar lessons for students who want help with chords and rhythm.', 'SERVICES', FALSE, 0, NULL, '2026-05-12 16:40:00', '2026-05-17 16:40:00', 'ACTIVE', NULL, NULL, 7, NULL),
    (17, 'Rice Cooker', 26.00, 'Small rice cooker for one or two people, includes measuring cup and spoon.', 'OTHER', TRUE, 0, NULL, '2026-05-08 10:25:00', '2026-05-14 10:25:00', 'SOLD', NULL, '2026-05-09 13:00:00', 8, 1),
    (18, 'Coffee Table', 32.00, 'Low coffee table with light scratches but a sturdy frame.', 'FURNITURE', FALSE, 0, NULL, '2026-05-07 12:10:00', '2026-05-13 12:10:00', 'ARCHIVED', '2026-05-13 11:05:00', NULL, 4, NULL)
ON CONFLICT (post_id) DO NOTHING;

INSERT INTO post_media (id, post_id, media_url, image_hash, display_order, is_cover) VALUES
    (1, 1, 'https://images.example.com/student-storefront/posts/1/calculator-front.jpg', 'a517a5a575887640c34f0adc4c3adb5d966a96206b25fa3fb0996fa10e83b96c', 0, TRUE),
    (2, 1, 'https://images.example.com/student-storefront/posts/1/calculator-back.jpg', '31c926d73a3e875721e01849d080ef8ca7cf6865afeffc28aa706c1642c4b780', 1, FALSE),
    (3, 2, 'https://images.example.com/student-storefront/posts/2/algorithms-book.jpg', 'ecbadcd41b070fbda896d477ab95f42d3ae2f7481c69bd0e90579c986b0d2508', 0, TRUE),
    (4, 3, 'https://images.example.com/student-storefront/posts/3/desk-lamp.jpg', '9625177bc398e61401f02cde058c1d0eb620a3155c7d31df95d14fe8fa99dc42', 0, TRUE),
    (5, 4, 'https://images.example.com/student-storefront/posts/4/fridge-closed.jpg', '70c7187c16b1bb8c408f3f655d382f0010905189614c84013610da5b0ff29c7d', 0, TRUE),
    (6, 4, 'https://images.example.com/student-storefront/posts/4/fridge-open.jpg', 'ff7ce6fa2092695c1b4c4840c1b2967d5335fd99275f116fec435a83eeef71d7', 1, FALSE),
    (7, 5, 'https://images.example.com/student-storefront/posts/5/office-chair.jpg', '1d67a2e85bf3e087333f7e6c899ea1b7a58273eddcea3b213ea4908e5af6ee67', 0, TRUE),
    (8, 6, 'https://images.example.com/student-storefront/posts/6/notes-stack.jpg', '89b3f99712c1aa3c4416d60813c6223c6c3418865c9d1538f1650578e745a9ef', 0, TRUE),
    (9, 7, 'https://images.example.com/student-storefront/posts/7/headphones-case.jpg', '2683f143c0b83e67e249ff92d92a71589261b751e44ffe62a8be2c7466417c44', 0, TRUE),
    (10, 7, 'https://images.example.com/student-storefront/posts/7/headphones-side.jpg', 'a608fb1205c7a13bdbec839ef8cbc52627f3e8d44c8d5484760e8d57b212dd3b', 1, FALSE),
    (11, 8, 'https://images.example.com/student-storefront/posts/8/boots-pair.jpg', '8fd1f1ab7551194d7d32d53e71d7bae72b4c413f4700f126c539f29c375bbaf5', 0, TRUE),
    (12, 8, 'https://images.example.com/student-storefront/posts/8/boots-sole.jpg', '76e2813118a96f402440cc27b0d81109fb681b17d3497f4d7c8591ad003761ae', 1, FALSE),
    (13, 9, 'https://images.example.com/student-storefront/posts/9/jacket.jpg', '07989d7f7c45a5e9f8a16af5399d9cbc1bc98a28b4947126c9aa1e000f44ccb1', 0, TRUE),
    (14, 10, 'https://images.example.com/student-storefront/posts/10/design-tutoring.jpg', '063786cb62045b101a27736e0b5d3650d7de5b4c5cb1018caa81414230a2204e', 0, TRUE),
    (15, 11, 'https://images.example.com/student-storefront/posts/11/lab-coat.jpg', 'b3bbd964e4fec1e1d68b03af13aa5c76f970c103212335baf54ac50bb93cc4aa', 0, TRUE),
    (16, 12, 'https://images.example.com/student-storefront/posts/12/bike-helmet.jpg', '1480ec65659ccd38eb229d827a96fb1fe01670c54b2f2aa2d7441f059e1c0615', 0, TRUE),
    (17, 13, 'https://images.example.com/student-storefront/posts/13/workbook.jpg', '1e2f51c41f4cb253830909e7c1ed2a7993387ee235575e2446d40e2d8ccf983b', 0, TRUE),
    (18, 14, 'https://images.example.com/student-storefront/posts/14/shelves-front.jpg', 'b54348e8569ce5280d926f62372f7bff98a5f6b0059cb5873cab61050cf78ef0', 0, TRUE),
    (19, 14, 'https://images.example.com/student-storefront/posts/14/shelves-side.jpg', '9c7fce6971e46662a97a638af8f7b899a912223d04e8da00108037ae80640295', 1, FALSE),
    (20, 15, 'https://images.example.com/student-storefront/posts/15/monitor-front.jpg', 'b0a3abe73ccc1235c1c8d59c00bf38682eaf01fa6eb0f30ac019a36917607ebd', 0, TRUE),
    (21, 15, 'https://images.example.com/student-storefront/posts/15/monitor-ports.jpg', 'bdd86c8da5ba30d41bf06ab09ca45f5ec097ef1d38e1305340e6121a820e9e4b', 1, FALSE),
    (22, 16, 'https://images.example.com/student-storefront/posts/16/guitar-lessons.jpg', 'f317866108c83f0838ffd97973eb7559720dbe243bca103a57437d08f1f4ff3d', 0, TRUE),
    (23, 17, 'https://images.example.com/student-storefront/posts/17/rice-cooker.jpg', 'b89614622e3d83aeb24b9f4b1caa7c501dc5b65ba333946b5c975936fa66d313', 0, TRUE),
    (24, 18, 'https://images.example.com/student-storefront/posts/18/coffee-table.jpg', '291fb0d8d0b68c3909d27391f9da59f5d05966ac740312d2b8431327522618f2', 0, TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO favourites (id, user_id, post_id, created_at) VALUES
    (1, 2, 1, '2026-05-13 18:00:00'),
    (2, 3, 15, '2026-05-13 21:30:00'),
    (3, 5, 2, '2026-05-13 10:15:00'),
    (4, 5, 10, '2026-05-14 10:00:00'),
    (5, 6, 1, '2026-05-14 08:10:00'),
    (6, 8, 14, '2026-05-14 09:05:00'),
    (7, 1, 7, '2026-05-11 12:15:00')
ON CONFLICT (id) DO NOTHING;

INSERT INTO sales (id, post_id, seller_user_id, buyer_user_id, sold_at, buyer_confirmed_at, title_snapshot, price_snapshot, created_at) VALUES
    (1, 5, 1, 5, '2026-05-12 15:00:00', '2026-05-12 16:00:00', 'Office Chair', 45.00, '2026-05-12 15:00:00'),
    (2, 6, 6, 2, '2026-05-10 14:00:00', '2026-05-10 15:00:00', 'Linear Algebra Notes', 15.00, '2026-05-10 14:00:00'),
    (3, 7, 7, 3, '2026-05-11 18:30:00', '2026-05-11 19:00:00', 'Bluetooth Headphones', 52.00, '2026-05-11 18:30:00'),
    (4, 17, 8, 1, '2026-05-09 13:00:00', '2026-05-09 14:00:00', 'Rice Cooker', 26.00, '2026-05-09 13:00:00')
ON CONFLICT (id) DO NOTHING;

INSERT INTO reviews (review_id, sale_id, reviewer_user_id, reviewee_user_id, direction, rating, comment, created_at) VALUES
    (1, 1, 5, 1, 'BUYER_TO_SELLER', 5, 'Chair matched the photos and pickup was easy.', '2026-05-12 18:00:00'),
    (2, 1, 1, 5, 'SELLER_TO_BUYER', 4, 'Quick payment and smooth communication.', '2026-05-12 19:00:00'),
    (3, 3, 3, 7, 'BUYER_TO_SELLER', 4, 'Headphones worked well and the battery was still strong.', '2026-05-11 20:30:00'),
    (4, 3, 7, 3, 'SELLER_TO_BUYER', 5, 'Buyer arrived on time and was easy to coordinate with.', '2026-05-11 21:10:00'),
    (5, 4, 1, 8, 'BUYER_TO_SELLER', 5, 'Rice cooker was clean and exactly as described.', '2026-05-09 15:00:00'),
    (6, 4, 8, 1, 'SELLER_TO_BUYER', 4, 'Prompt pickup and clear communication from the buyer.', '2026-05-09 15:20:00')
ON CONFLICT (review_id) DO NOTHING;

INSERT INTO password_reset_tokens (id, token_hash, user_id, expires_at, used, created_at) VALUES
    (1, '046f94273f69d1950eb130ac2defffed0c2398567a2830c9b76fb0d17dc83732', 2, '2026-05-14 18:00:00', FALSE, '2026-05-14 17:30:00'),
    (2, 'ac9def7f77205d366f66dfc0c05c8b870535ec6f9e63f09ae8df9634ded6b313', 5, '2026-05-13 20:00:00', TRUE, '2026-05-13 19:30:00')
ON CONFLICT (id) DO NOTHING;

INSERT INTO email_verification_tokens (id, code_hash, user_id, expires_at, used, target_email, attempts, created_at) VALUES
    (1, '$2a$10$GpScYuavP.Z18cEjykoPO.41ee2jK2yRkBx1uQQMVUmBgItuzCphi', 9, '2026-05-14 12:15:00', FALSE, NULL, 0, '2026-05-14 12:00:00'),
    (2, '$2a$10$2WGPQoi5tFAyjsui6dOao.qbbBgE.zUc/HUc9XryriU/Ja9Iy1cVW', 3, '2026-05-01 09:00:00', TRUE, NULL, 0, '2026-05-01 08:45:00')
ON CONFLICT (id) DO NOTHING;

INSERT INTO whatsapp_login_sessions (
    id, session_id, login_token, claim_token, status, user_id, phone_number, creator_ip,
    created_at, expires_at, completed_at, claimed_at
) VALUES
    ('11111111-1111-1111-1111-111111111111', '21111111-1111-1111-1111-111111111111', '31111111-1111-1111-1111-111111111111', NULL, 'PENDING', NULL, '+491111111102', '127.0.0.1', '2026-05-14 11:58:00', '2026-05-14 12:03:00', NULL, NULL),
    ('11111111-1111-1111-1111-111111111112', '21111111-1111-1111-1111-111111111112', '31111111-1111-1111-1111-111111111112', '41111111-1111-1111-1111-111111111112', 'COMPLETED', 1, '+491111111101', '127.0.0.1', '2026-05-14 11:50:00', '2026-05-14 11:55:00', '2026-05-14 11:53:00', NULL),
    ('11111111-1111-1111-1111-111111111113', '21111111-1111-1111-1111-111111111113', '31111111-1111-1111-1111-111111111113', '41111111-1111-1111-1111-111111111113', 'CLAIMED', 8, '+491111111108', '127.0.0.1', '2026-05-14 10:00:00', '2026-05-14 10:05:00', '2026-05-14 10:02:00', '2026-05-14 10:03:00'),
    ('11111111-1111-1111-1111-111111111114', '21111111-1111-1111-1111-111111111114', '31111111-1111-1111-1111-111111111114', NULL, 'PHONE_NOT_LINKED', NULL, '+491111111199', '127.0.0.1', '2026-05-14 11:40:00', '2026-05-14 11:45:00', NULL, NULL),
    ('11111111-1111-1111-1111-111111111115', '21111111-1111-1111-1111-111111111115', '31111111-1111-1111-1111-111111111115', NULL, 'EXPIRED', 5, '+491111111105', '127.0.0.1', '2026-05-14 10:40:00', '2026-05-14 10:45:00', NULL, NULL)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('users', 'user_id'), COALESCE((SELECT MAX(user_id) FROM users), 1), TRUE);
SELECT setval(pg_get_serial_sequence('posts', 'post_id'), COALESCE((SELECT MAX(post_id) FROM posts), 1), TRUE);
SELECT setval(pg_get_serial_sequence('post_media', 'id'), COALESCE((SELECT MAX(id) FROM post_media), 1), TRUE);
SELECT setval(pg_get_serial_sequence('favourites', 'id'), COALESCE((SELECT MAX(id) FROM favourites), 1), TRUE);
SELECT setval(pg_get_serial_sequence('sales', 'id'), COALESCE((SELECT MAX(id) FROM sales), 1), TRUE);
SELECT setval(pg_get_serial_sequence('reviews', 'review_id'), COALESCE((SELECT MAX(review_id) FROM reviews), 1), TRUE);
SELECT setval(pg_get_serial_sequence('password_reset_tokens', 'id'), COALESCE((SELECT MAX(id) FROM password_reset_tokens), 1), TRUE);
SELECT setval(pg_get_serial_sequence('email_verification_tokens', 'id'), COALESCE((SELECT MAX(id) FROM email_verification_tokens), 1), TRUE);

COMMIT;
