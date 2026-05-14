BEGIN;

INSERT INTO sellers (seller_id, name, email, phone_number, password, role, is_enabled, created_at) VALUES
    (1, 'Alice Johnson', 'alice.johnson@constructor.university', '+491111111101', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'SELLER', TRUE, '2026-05-01 09:00:00'),
    (2, 'Brian Lee', 'brian.lee@constructor.university', '+491111111102', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'SELLER', TRUE, '2026-05-01 09:05:00'),
    (3, 'Carla Mendes', 'carla.mendes@constructor.university', '+491111111103', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'SELLER', TRUE, '2026-05-01 09:10:00'),
    (4, 'Diego Novak', 'diego.novak@constructor.university', '+491111111104', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'SELLER', TRUE, '2026-05-01 09:15:00'),
    (5, 'Emma Fischer', 'emma.fischer@constructor.university', '+491111111105', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'SELLER', TRUE, '2026-05-01 09:20:00'),
    (6, 'Farah Khan', 'farah.khan@constructor.university', '+491111111106', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'SELLER', TRUE, '2026-05-01 09:25:00'),
    (7, 'Gregor Ivanov', 'gregor.ivanov@constructor.university', '+491111111107', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'SELLER', TRUE, '2026-05-01 09:30:00'),
    (8, 'Hana Petrov', 'hana.petrov@constructor.university', '+491111111108', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'SELLER', TRUE, '2026-05-01 09:35:00'),
    (9, 'Ivan Schmidt', 'ivan.schmidt@constructor.university', '+491111111109', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'SELLER', FALSE, '2026-05-14 11:55:00'),
    (10, 'Admin User', 'admin@constructor.university', '+491111111110', '$2a$10$WZlmLD.UxKoLUI8nDkdX6uZy6nTA17HW0MaM3nTxmbFHQm1MYflWi', 'ADMIN', TRUE, '2026-05-01 09:40:00')
ON CONFLICT (seller_id) DO NOTHING;

INSERT INTO posts (
    post_id, title, price, description, category, is_sold, created_at, expires_at, status,
    reminder_sent_at, sold_at, seller_id, buyer_id
) VALUES
    (1, 'TI-84 Plus Calculator', 65.00, 'Graphing calculator in good condition, ideal for calculus and statistics classes.', 'ELECTRONICS', FALSE, '2026-05-13 09:00:00', '2026-05-15 09:00:00', 'ACTIVE', NULL, NULL, 1, NULL),
    (2, 'Algorithms Textbook', 28.00, 'Introduction to Algorithms third edition with light highlighting in chapters 1 to 6.', 'BOOKS', FALSE, '2026-05-12 17:30:00', '2026-05-15 17:30:00', 'ACTIVE', NULL, NULL, 2, NULL),
    (3, 'Desk Lamp', 18.00, 'LED desk lamp with adjustable neck, perfect for late-night study sessions.', 'FURNITURE', FALSE, '2026-05-14 08:45:00', '2026-05-14 12:30:00', 'ACTIVE', NULL, NULL, 3, NULL),
    (4, 'Mini Fridge', 110.00, 'Compact dorm fridge with freezer compartment and clean interior.', 'ELECTRONICS', FALSE, '2026-05-11 11:00:00', '2026-05-14 11:30:00', 'ARCHIVED', '2026-05-14 10:35:00', NULL, 4, NULL),
    (5, 'Office Chair', 45.00, 'Black swivel chair with adjustable height and minor wear on the armrests.', 'FURNITURE', TRUE, '2026-05-08 14:00:00', '2026-05-16 14:00:00', 'ACTIVE', NULL, '2026-05-12 15:00:00', 1, 5),
    (6, 'Linear Algebra Notes', 15.00, 'Printed and organized lecture notes for Linear Algebra, including solved exercises.', 'BOOKS', TRUE, '2026-05-09 12:00:00', '2026-05-16 12:00:00', 'ACTIVE', NULL, '2026-05-10 14:00:00', 6, 2),
    (7, 'Bluetooth Headphones', 52.00, 'Over-ear Bluetooth headphones with charger cable and working noise cancellation.', 'ELECTRONICS', TRUE, '2026-05-10 18:00:00', '2026-05-17 18:00:00', 'ACTIVE', NULL, '2026-05-11 18:30:00', 7, 3),
    (8, 'Football Boots', 35.00, 'Nike football boots size 43, used for one season but still in strong condition.', 'SPORTS', FALSE, '2026-05-13 19:30:00', '2026-05-16 19:30:00', 'ACTIVE', NULL, NULL, 8, NULL),
    (9, 'Winter Jacket', 40.00, 'Warm winter jacket, size M, recently washed and ready for pickup.', 'CLOTHING', FALSE, '2026-05-05 09:15:00', '2026-05-11 09:15:00', 'ARCHIVED', '2026-05-11 08:20:00', NULL, 5, NULL),
    (10, 'Graphic Design Tutoring', 20.00, 'One-hour tutoring sessions for Photoshop and Illustrator assignments.', 'SERVICES', FALSE, '2026-05-14 09:30:00', '2026-05-16 09:30:00', 'ACTIVE', NULL, NULL, 4, NULL),
    (11, 'Chemistry Lab Coat', 12.00, 'Clean lab coat, size L, suitable for introductory chemistry labs.', 'CLOTHING', FALSE, '2026-05-13 13:20:00', '2026-05-15 13:20:00', 'ACTIVE', NULL, NULL, 2, NULL),
    (12, 'Bike Helmet', 22.00, 'Adjustable bike helmet with removable visor and intact interior padding.', 'SPORTS', FALSE, '2026-05-14 10:10:00', '2026-05-14 12:45:00', 'ACTIVE', NULL, NULL, 3, NULL),
    (13, 'Macroeconomics Workbook', 17.00, 'Workbook with practice questions and answer key for first-year macroeconomics.', 'BOOKS', FALSE, '2026-05-12 08:50:00', '2026-05-15 08:50:00', 'ACTIVE', NULL, NULL, 6, NULL),
    (14, 'Dorm Storage Shelves', 30.00, 'Two-tier storage shelves that fit beside a dorm desk or under a lofted bed.', 'FURNITURE', FALSE, '2026-05-14 07:55:00', '2026-05-15 07:55:00', 'ACTIVE', NULL, NULL, 5, NULL),
    (15, 'Gaming Monitor 24 Inch', 95.00, '24-inch full HD monitor with HDMI cable included and no dead pixels.', 'ELECTRONICS', FALSE, '2026-05-13 21:10:00', '2026-05-15 21:10:00', 'ACTIVE', NULL, NULL, 1, NULL),
    (16, 'Acoustic Guitar Lessons', 25.00, 'Beginner guitar lessons for students who want help with chords and rhythm.', 'SERVICES', FALSE, '2026-05-12 16:40:00', '2026-05-17 16:40:00', 'ACTIVE', NULL, NULL, 7, NULL),
    (17, 'Rice Cooker', 26.00, 'Small rice cooker for one or two people, includes measuring cup and spoon.', 'OTHER', TRUE, '2026-05-08 10:25:00', '2026-05-14 10:25:00', 'ACTIVE', NULL, '2026-05-09 13:00:00', 8, 1),
    (18, 'Coffee Table', 32.00, 'Low coffee table with light scratches but a sturdy frame.', 'FURNITURE', FALSE, '2026-05-07 12:10:00', '2026-05-13 12:10:00', 'ARCHIVED', '2026-05-13 11:05:00', NULL, 4, NULL)
ON CONFLICT (post_id) DO NOTHING;

INSERT INTO post_media (id, post_id, media_url, image_hash, display_order, is_cover) VALUES
    (1, 1, 'https://images.example.com/student-storefront/posts/1/calculator-front.jpg', 'hash-post1-a', 0, TRUE),
    (2, 1, 'https://images.example.com/student-storefront/posts/1/calculator-back.jpg', 'hash-post1-b', 1, FALSE),
    (3, 2, 'https://images.example.com/student-storefront/posts/2/algorithms-book.jpg', 'hash-post2-a', 0, TRUE),
    (4, 3, 'https://images.example.com/student-storefront/posts/3/desk-lamp.jpg', 'hash-post3-a', 0, TRUE),
    (5, 4, 'https://images.example.com/student-storefront/posts/4/fridge-closed.jpg', 'hash-post4-a', 0, TRUE),
    (6, 4, 'https://images.example.com/student-storefront/posts/4/fridge-open.jpg', 'hash-post4-b', 1, FALSE),
    (7, 5, 'https://images.example.com/student-storefront/posts/5/office-chair.jpg', 'hash-post5-a', 0, TRUE),
    (8, 6, 'https://images.example.com/student-storefront/posts/6/notes-stack.jpg', 'hash-post6-a', 0, TRUE),
    (9, 7, 'https://images.example.com/student-storefront/posts/7/headphones-case.jpg', 'hash-post7-a', 0, TRUE),
    (10, 7, 'https://images.example.com/student-storefront/posts/7/headphones-side.jpg', 'hash-post7-b', 1, FALSE),
    (11, 8, 'https://images.example.com/student-storefront/posts/8/boots-pair.jpg', 'hash-post8-a', 0, TRUE),
    (12, 8, 'https://images.example.com/student-storefront/posts/8/boots-sole.jpg', 'hash-post8-b', 1, FALSE),
    (13, 9, 'https://images.example.com/student-storefront/posts/9/jacket.jpg', 'hash-post9-a', 0, TRUE),
    (14, 10, 'https://images.example.com/student-storefront/posts/10/design-tutoring.jpg', 'hash-post10-a', 0, TRUE),
    (15, 11, 'https://images.example.com/student-storefront/posts/11/lab-coat.jpg', 'hash-post11-a', 0, TRUE),
    (16, 12, 'https://images.example.com/student-storefront/posts/12/bike-helmet.jpg', 'hash-post12-a', 0, TRUE),
    (17, 13, 'https://images.example.com/student-storefront/posts/13/workbook.jpg', 'hash-post13-a', 0, TRUE),
    (18, 14, 'https://images.example.com/student-storefront/posts/14/shelves-front.jpg', 'hash-post14-a', 0, TRUE),
    (19, 14, 'https://images.example.com/student-storefront/posts/14/shelves-side.jpg', 'hash-post14-b', 1, FALSE),
    (20, 15, 'https://images.example.com/student-storefront/posts/15/monitor-front.jpg', 'hash-post15-a', 0, TRUE),
    (21, 15, 'https://images.example.com/student-storefront/posts/15/monitor-ports.jpg', 'hash-post15-b', 1, FALSE),
    (22, 16, 'https://images.example.com/student-storefront/posts/16/guitar-lessons.jpg', 'hash-post16-a', 0, TRUE),
    (23, 17, 'https://images.example.com/student-storefront/posts/17/rice-cooker.jpg', 'hash-post17-a', 0, TRUE),
    (24, 18, 'https://images.example.com/student-storefront/posts/18/coffee-table.jpg', 'hash-post18-a', 0, TRUE)
ON CONFLICT (id) DO NOTHING;

INSERT INTO favourites (id, seller_id, post_id, created_at) VALUES
    (1, 2, 1, '2026-05-13 18:00:00'),
    (2, 3, 15, '2026-05-13 21:30:00'),
    (3, 5, 2, '2026-05-13 10:15:00'),
    (4, 5, 10, '2026-05-14 10:00:00'),
    (5, 6, 1, '2026-05-14 08:10:00'),
    (6, 8, 14, '2026-05-14 09:05:00'),
    (7, 1, 7, '2026-05-11 12:15:00')
ON CONFLICT (id) DO NOTHING;

INSERT INTO reviews (review_id, post_id, reviewer_id, reviewee_id, direction, rating, comment, created_at) VALUES
    (1, 5, 5, 1, 'BUYER_TO_SELLER', 5, 'Chair matched the photos and pickup was easy.', '2026-05-12 18:00:00'),
    (2, 5, 1, 5, 'SELLER_TO_BUYER', 4, 'Quick payment and smooth communication.', '2026-05-12 19:00:00'),
    (3, 7, 3, 7, 'BUYER_TO_SELLER', 4, 'Headphones worked well and the battery was still strong.', '2026-05-11 20:30:00'),
    (4, 7, 7, 3, 'SELLER_TO_BUYER', 5, 'Buyer arrived on time and was easy to coordinate with.', '2026-05-11 21:10:00'),
    (5, 17, 1, 8, 'BUYER_TO_SELLER', 5, 'Rice cooker was clean and exactly as described.', '2026-05-09 15:00:00'),
    (6, 17, 8, 1, 'SELLER_TO_BUYER', 4, 'Prompt pickup and clear communication from the buyer.', '2026-05-09 15:20:00')
ON CONFLICT (review_id) DO NOTHING;

INSERT INTO password_reset_tokens (id, token, seller_id, expires_at, used, created_at) VALUES
    (1, 'reset-brian-20260514', 2, '2026-05-14 18:00:00', FALSE, '2026-05-14 17:30:00'),
    (2, 'reset-emma-used-20260513', 5, '2026-05-13 20:00:00', TRUE, '2026-05-13 19:30:00')
ON CONFLICT (id) DO NOTHING;

INSERT INTO email_verification_tokens (id, code, seller_id, expires_at, used, created_at) VALUES
    (1, '483921', 9, '2026-05-14 12:15:00', FALSE, '2026-05-14 12:00:00'),
    (2, '148305', 3, '2026-05-01 09:00:00', TRUE, '2026-05-01 08:45:00')
ON CONFLICT (id) DO NOTHING;

INSERT INTO whatsapp_login_sessions (
    id, session_id, login_token, claim_token, status, seller_id, phone_number, creator_ip,
    created_at, expires_at, completed_at, claimed_at
) VALUES
    ('11111111-1111-1111-1111-111111111111', '21111111-1111-1111-1111-111111111111', '31111111-1111-1111-1111-111111111111', NULL, 'PENDING', 2, '+491111111102', '127.0.0.1', '2026-05-14 11:58:00', '2026-05-14 12:03:00', NULL, NULL),
    ('11111111-1111-1111-1111-111111111112', '21111111-1111-1111-1111-111111111112', '31111111-1111-1111-1111-111111111112', '41111111-1111-1111-1111-111111111112', 'COMPLETED', 1, '+491111111101', '127.0.0.1', '2026-05-14 11:50:00', '2026-05-14 11:55:00', '2026-05-14 11:53:00', NULL),
    ('11111111-1111-1111-1111-111111111113', '21111111-1111-1111-1111-111111111113', '31111111-1111-1111-1111-111111111113', '41111111-1111-1111-1111-111111111113', 'CLAIMED', 8, '+491111111108', '127.0.0.1', '2026-05-14 10:00:00', '2026-05-14 10:05:00', '2026-05-14 10:02:00', '2026-05-14 10:03:00'),
    ('11111111-1111-1111-1111-111111111114', '21111111-1111-1111-1111-111111111114', '31111111-1111-1111-1111-111111111114', NULL, 'PHONE_NOT_LINKED', NULL, '+491111111199', '127.0.0.1', '2026-05-14 11:40:00', '2026-05-14 11:45:00', NULL, NULL),
    ('11111111-1111-1111-1111-111111111115', '21111111-1111-1111-1111-111111111115', '31111111-1111-1111-1111-111111111115', NULL, 'EXPIRED', 5, '+491111111105', '127.0.0.1', '2026-05-14 10:40:00', '2026-05-14 10:45:00', NULL, NULL)
ON CONFLICT (id) DO NOTHING;

SELECT setval(pg_get_serial_sequence('sellers', 'seller_id'), COALESCE((SELECT MAX(seller_id) FROM sellers), 1), TRUE);
SELECT setval(pg_get_serial_sequence('posts', 'post_id'), COALESCE((SELECT MAX(post_id) FROM posts), 1), TRUE);
SELECT setval(pg_get_serial_sequence('post_media', 'id'), COALESCE((SELECT MAX(id) FROM post_media), 1), TRUE);
SELECT setval(pg_get_serial_sequence('favourites', 'id'), COALESCE((SELECT MAX(id) FROM favourites), 1), TRUE);
SELECT setval(pg_get_serial_sequence('reviews', 'review_id'), COALESCE((SELECT MAX(review_id) FROM reviews), 1), TRUE);
SELECT setval(pg_get_serial_sequence('password_reset_tokens', 'id'), COALESCE((SELECT MAX(id) FROM password_reset_tokens), 1), TRUE);
SELECT setval(pg_get_serial_sequence('email_verification_tokens', 'id'), COALESCE((SELECT MAX(id) FROM email_verification_tokens), 1), TRUE);

COMMIT;
