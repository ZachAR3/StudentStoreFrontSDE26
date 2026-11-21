-- Seller-recorded sales remain pending until the selected buyer confirms them.
-- Existing sales are historical records and are treated as confirmed so their
-- existing review history remains visible after the new workflow is deployed.

ALTER TABLE sales
    ADD COLUMN IF NOT EXISTS buyer_confirmed_at TIMESTAMP;

UPDATE sales
SET buyer_confirmed_at = sold_at
WHERE buyer_confirmed_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_sales_buyer_confirmation
    ON sales(buyer_user_id, buyer_confirmed_at, sold_at DESC);
