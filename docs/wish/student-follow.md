# Student follow implementation

The student relationship is an academy-scoped source -> target follow. Canonical student row locks serialize all directional changes and global blocks. Follow/unfollow validate current memberships and bilateral blocks without requiring a card account. Duplicate commands retain the current start time; refollow advances the current start time at PostgreSQL microsecond precision and allocates a new activation.

Following and followers use independent direction flags, nickname filtering and whole-academy counts in a repeatable-read transaction. Cursor context includes actor, academy, direction, normalized filter, activation watermark, initial PostgreSQL MVCC snapshot and final sort tuple. Snapshot visibility excludes a transaction that was in flight when traversal began even if another transaction committed a higher sequence first. HMAC signing uses a database-generated key retained in relationship_cursor_key across application instances and fixture resets. Schema reinitialization starts a new key and invalidates prior cursors.

FOLLOWERS shared-card reads require viewer -> owner. Academy/private behavior and bilateral global blocks retain precedence. Blocking ends every academy direction atomically; releasing either direction never restores follows.

V1 and V6 initialize a clean database without friendship or friend_request tables. Existing synthetic token/persona identifiers are retained to avoid changing authentication configuration; the friend persona now follows the owner. Recommendation fixtures explicitly create the opposite direction when the owner must view that persona's restricted wish.

The approved OpenAPI bytes include YAML aliases. Runtime loading expands aliases with SnakeYAML SafeConstructor before creating the Jackson tree so aliases remain structured responses. The published contract bytes remain unchanged.
