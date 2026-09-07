# Weekly success-story completion cards

Weekly recap retrieval enriches the stored page 3 candidates with current public completion-card display fields. The stored wish ID and nullable historical type title remain unchanged. The response adds kind, owner nickname, purpose, target amount, progress, calendar dates, creation/completion times, actual duration, photo and content update time. It retains the existing ownerStudentId and sharedCardId without adding ownerId.

The viewer must still belong to the account academy. A batched lookup requires an active owner membership, an open owner account, a nondeleted COMPLETED wish with completedAt, and a COMPLETION shared card. The existing peer rules exclude self, either block direction and inaccessible FOLLOWERS cards; only viewer-to-owner following grants FOLLOWERS access. Missing or now-private cards are omitted. Candidates stay in stored order, at most five, without reranking or backfilling. Redaction updates the existing summary; an originally empty page preserves its original summary.

Both shared-card detail and weekly enrichment use SharedCardCompletionMapper. Dates preserve stored LocalDate values or null. Duration is max(0, seconds between createdAt and completedAt), independent of those calendar dates. Progress is always 100.

WishPhotoService is called only after current authorization. No attached photo yields null. An attached photo uses a fresh whole-second 300-second signing window. Delivery failure propagates PHOTO_DELIVERY_UNAVAILABLE; a disabled attached-photo runtime propagates PHOTO_PROCESSING_UNAVAILABLE. Both are retryable HTTP 503, and recap responses retain Cache-Control: no-store. Errors cannot silently become missing stories or null photos.

Enrichment changes only the response. It never rewrites stored Python views, requests, historical calculations, generation versions, algorithm versions, generatedAt, or card publication timestamps. Monthly responses and generation protocols are unchanged. No schema migration is required.

Behavioral coverage lives in RecapQueryServicePrivacyTest, SharedCardCompletionMapperTest and RecapSuccessStoryApiIT. API tests use PostgreSQL and real WishPhotoService with a local mocked storage signer; they cover current eligibility, directional follow/block rules, fresh photo URLs, retryable delivery failure, shared detail parity and unchanged persisted generation rows. RecapStorageIntegrityIT retains restart and generation-state coverage. The Python parity test requires CRABIT_RECAP_PARITY_CONFIG and is skipped without that external fixture.
