CREATE INDEX idx_shared_card_feed_order
    ON shared_card (updated_at DESC, id DESC);
