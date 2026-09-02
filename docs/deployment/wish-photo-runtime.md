# Private Wish photo runtime

Code delivery does not activate cloud traffic. `CRABIT_WISH_PHOTO_ENABLED` defaults
to `false`; explicitly empty or non-boolean values are rejected by the renderer.
Enable only through a separately authorized environment deployment after provider
inventory and private-object/signing read-back. Frontend integration is separate.

The renderer derives environment, project ID/number, bucket and runtime service
account. Enabled bindings are restricted to project
`project-9ee29576-dd79-4a1c-a70` / `182907578804`, environments `staging` and
`stable-demo`, bucket `crabit-wish-photo-<environment>-182907578804`, and account
`crabit-<environment>-runtime@<project>.iam.gserviceaccount.com`. Arbitrary overrides,
cross-environment values, duplicate keys, unsafe lines and non-0600 files fail closed.
No key, token or signed URL belongs in the runtime file.

## Identity and dependency boundaries

Disabled startup and explicit fake adapter overrides do not discover credentials.
Enabled runtime reads only the fixed GCE metadata endpoint and checks attached
project, project number and default service-account email. There is no ADC, user
credential, JSON key, HMAC key or alternate metadata-host fallback. Metadata calls
have 1-second connection and 2-second request limits. SDK operations use one
attempt with 2-second RPC limits; each three-object operation has an 8-second
aggregate budget. Failures expose sanitized existing processing/delivery errors.

Vision sees canonical JPEG bytes and only SafeSearch. Missing/unknown required
adult, racy or violence results fail closed; LIKELY/VERY_LIKELY reject content.
Objects use three fixed JPEG keys with private five-minute metadata and no ACL.
Writes require absence and have no automatic retries; failures enter existing
compensation/durable cleanup. Missing-object deletes succeed, failed deletes retry.

All V4 GET URLs use one whole-second reference and exactly 300 encoded seconds.
IAM signs as the same runtime service account; no partial result is returned.
Signing time consumes that original window. `expiresAt` is its exact deadline.
The signing-only SDK clock is fixed; request budgets and photo lifecycle clocks
remain live. The typed photo clock does not replace the fixed e2e domain clock.

## Reset and recovery

V13 preserves cleanup identities and retry state before fixture rows disappear.
Conflicting photo ID/prefix bindings abort reset transactionally. The offline
`demo-reset` container explicitly disables the photo runtime; the serving backend
performs queued deletion after restart. Do not manually clear the production
cleanup queue. Monitor queue size, oldest request age and attempt counts without
logging object prefixes, signed URLs, bytes or safety/provider responses.

For provider failure, restore the scoped permission/service dependency and allow
the durable worker to retry. Do not re-provision, delete buckets, rotate identities
or retry ambiguous provider writes as an application recovery shortcut.

## Verification and activation checklist

- Run `verify-wish-photo-runtime.sh`, Google Cloud/workflow regressions, photo and
  PostgreSQL suites, unchanged OpenAPI checks, full tests and packaging/image checks.
- Separately read back private bucket policy, region, runtime IAM isolation,
  metadata identity, Vision access and self-only IAM signing before activation.
- Prove actual object privacy, shared URL expiration, cleanup failure/retry and
  reset/restart behavior under the live enabled runtime before user traffic.
- Keep the opt-in false until those separately authorized checks are complete.
