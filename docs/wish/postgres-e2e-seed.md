# PostgreSQL migration and E2E Seed

PostgreSQL schema creation is owned by Flyway. `V1__wish_schema.sql` creates the fourteen Wish data-model tables, portable checks and foreign keys, and the PostgreSQL current-row unique indexes. Both `prod` and `e2e` use `spring.jpa.hibernate.ddl-auto=validate`; the default test profile keeps Flyway disabled so the existing H2 unit and slice tests remain isolated from PostgreSQL evidence.

## Profiles

- `prod`: set `CRABIT_DATABASE_URL`, `CRABIT_DATABASE_USERNAME`, and `CRABIT_DATABASE_PASSWORD`. Flyway runs and Hibernate validates; no Seed bean is active.
- `e2e`: provide a PostgreSQL datasource. Flyway runs, Hibernate validates, and deterministic Seed fixtures and Bearer authentication are active.
- Set `crabit.e2e.seed.reset-on-startup=true` only for an isolated E2E database when startup must remove Seed-owned rows and restore the canonical fixture. The default is idempotent insert-only initialization.

## Deterministic personas and tokens

| Persona | Token | Principal | Academy relation |
|---|---|---|---|
| owner | `seed-owner-token` | student `...0201` | primary academy, active account |
| same-academy friend | `seed-friend-token` | student `...0202` | current friendship with owner |
| same-academy nonfriend | `seed-nonfriend-token` | student `...0203` | current membership, no friendship |
| blocked student | `seed-blocked-token` | student `...0204` | owner has a current block |
| other-academy student | `seed-other-academy-token` | student `...0205` | other academy only |
| same-academy staff | `seed-staff-token` | staff `...0206` | in-memory E2E principal, not a student row |

The owner account is `00000000-0000-0000-0000-000000000301`. The initial Wishes are `...0401` (노트북, in progress, friends visibility) and `...0402` (여름 캠프, amount reached, academy visibility). IDs, timestamps, relationships, amounts, and tokens remain fixed across initialization and reset.

Missing and unknown tokens return `401 AUTH_REQUIRED` with `WWW-Authenticate: Bearer`. Known staff tokens authenticate but receive `403 FORBIDDEN` on the student-only `/v1/**` surface. There is no default principal fallback.

## Verification

The PostgreSQL checks require a working Docker daemon and never substitute H2:

```shell
./gradlew test --tests '*PostgresMigrationIT' --tests '*DatabaseConstraintIT' --tests '*SeedFixtureIT' --tests '*SeedAuthenticationIT' --console=plain
./gradlew test --console=plain
```
