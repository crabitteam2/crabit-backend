#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  echo "usage: $0 BACKEND_WORKTREE BACKEND_SHA DATA_WORKTREE DATA_SHA" >&2
  exit 64
}

fail() {
  echo "verify-feed-integration: $*" >&2
  exit 1
}

[[ $# -eq 4 ]] || usage
backend_worktree=$1
backend_sha=$2
data_worktree=$3
data_sha=$4

for command in git docker curl jq python3; do
  command -v "$command" >/dev/null 2>&1 || fail "missing prerequisite: $command"
done

canonical_worktree() {
  local requested=$1
  [[ -d "$requested" ]] || fail "worktree does not exist: $requested"
  (cd "$requested" && pwd -P)
}

verify_binding() {
  local label=$1 worktree=$2 expected=$3 actual_root actual_sha
  [[ "$expected" =~ ^[0-9a-f]{40}$ ]] || fail "$label SHA must be a full lowercase 40-character commit SHA"
  actual_root=$(git -C "$worktree" rev-parse --show-toplevel) || fail "$label is not a Git worktree"
  [[ "$(cd "$actual_root" && pwd -P)" == "$worktree" ]] || fail "$label path is not its Git worktree root"
  actual_sha=$(git -C "$worktree" rev-parse HEAD)
  [[ "$actual_sha" == "$expected" ]] || fail "$label HEAD mismatch: expected $expected, got $actual_sha"
}

backend_worktree=$(canonical_worktree "$backend_worktree")
data_worktree=$(canonical_worktree "$data_worktree")
verify_binding backend "$backend_worktree" "$backend_sha"
verify_binding data "$data_worktree" "$data_sha"
[[ -x "$backend_worktree/gradlew" ]] || fail "backend prerequisite missing: executable gradlew"
[[ -f "$data_worktree/feed_service/__main__.py" ]] || fail "data prerequisite missing: feed_service/__main__.py"

tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/crabit-feed-integration.XXXXXX")
container="crabit-feed-integration-${RANDOM}-$$"
postgres_port=
backend_port=
python_port=
backend_pid=
python_pid=

cleanup() {
  local status=$?
  trap - EXIT INT TERM
  [[ -z "$backend_pid" ]] || kill "$backend_pid" 2>/dev/null || true
  [[ -z "$python_pid" ]] || kill "$python_pid" 2>/dev/null || true
  [[ -z "$backend_pid" ]] || wait "$backend_pid" 2>/dev/null || true
  [[ -z "$python_pid" ]] || wait "$python_pid" 2>/dev/null || true
  docker rm -f "$container" >/dev/null 2>&1 || true
  rm -rf "$tmp_dir"
  exit "$status"
}
trap cleanup EXIT INT TERM

free_port() {
  python3 -c 'import socket; s=socket.socket(); s.bind(("127.0.0.1",0)); print(s.getsockname()[1]); s.close()'
}
postgres_port=$(free_port)
backend_port=$(free_port)
python_port=$(free_port)
[[ "$postgres_port" != "$backend_port" && "$postgres_port" != "$python_port" && "$backend_port" != "$python_port" ]] \
  || fail "ephemeral port collision; rerun the verifier"

credential="feed-integration-${RANDOM}-$$"
db_password="postgres-${RANDOM}-$$"
docker run --detach --name "$container" \
  --publish "127.0.0.1:${postgres_port}:5432" \
  --env POSTGRES_DB=crabit --env POSTGRES_USER=crabit --env "POSTGRES_PASSWORD=$db_password" \
  postgres:16-alpine >"$tmp_dir/container.id"

for _ in $(seq 1 60); do
  docker exec "$container" pg_isready -U crabit -d crabit >/dev/null 2>&1 && break
  sleep 1
done
docker exec "$container" pg_isready -U crabit -d crabit >/dev/null 2>&1 \
  || fail "isolated PostgreSQL did not become ready"

(
  cd "$data_worktree"
  exec env \
    PORT="$python_port" HOST=127.0.0.1 \
    FEED_RANKING_CREDENTIAL="$credential" CRABIT_FEED_RANKING_CREDENTIAL="$credential" \
    python3 -m feed_service --host 127.0.0.1 --port "$python_port"
) >"$tmp_dir/python.log" 2>&1 &
python_pid=$!

for _ in $(seq 1 30); do
  kill -0 "$python_pid" 2>/dev/null || {
    sed -n '1,160p' "$tmp_dir/python.log" >&2
    fail "authenticated Python feed service exited before becoming ready"
  }
  curl --silent --fail "http://127.0.0.1:${python_port}/health" >/dev/null 2>&1 && break
  sleep 1
done
curl --silent --fail "http://127.0.0.1:${python_port}/health" >/dev/null 2>&1 \
  || fail "authenticated Python feed service did not become ready at /health"

classifier_version="wish-category-v1@sha256:de23b80260907e3d818892c0ea6ba2d9d28251e49d75a812fb925e1a47733f61"
(
  cd "$backend_worktree"
  exec env \
    SERVER_PORT="$backend_port" SPRING_PROFILES_ACTIVE=e2e \
    CRABIT_DATABASE_URL="jdbc:postgresql://127.0.0.1:${postgres_port}/crabit" \
    CRABIT_DATABASE_USERNAME=crabit CRABIT_DATABASE_PASSWORD="$db_password" \
    CRABIT_E2E_SEED_RESET_ON_STARTUP=true \
    CRABIT_FEED_RANKING_ENABLED=true \
    CRABIT_FEED_RANKING_URL="http://127.0.0.1:${python_port}/internal/v1/feed-rankings" \
    CRABIT_FEED_RANKING_CREDENTIAL="$credential" \
    CRABIT_FEED_CLASSIFIER_VERSION="$classifier_version" \
    ./gradlew bootRun --console=plain
) >"$tmp_dir/backend.log" 2>&1 &
backend_pid=$!

for _ in $(seq 1 120); do
  kill -0 "$backend_pid" 2>/dev/null || {
    sed -n '1,200p' "$tmp_dir/backend.log" >&2
    fail "backend exited before becoming ready"
  }
  curl --silent --fail "http://127.0.0.1:${backend_port}/actuator/health/readiness" >/dev/null 2>&1 && break
  sleep 1
done
curl --silent --fail "http://127.0.0.1:${backend_port}/actuator/health/readiness" >/dev/null 2>&1 \
  || fail "backend did not become ready"

academy_id=00000000-0000-0000-0000-000000000101
http_status=$(curl --silent --show-error \
  --output "$tmp_dir/feed.json" --write-out '%{http_code}' \
  --request POST "http://127.0.0.1:${backend_port}/v1/academies/${academy_id}/feed-results" \
  --header 'Authorization: Bearer seed-friend-token' \
  --header 'Content-Type: application/json' \
  --data '{"limit":2}')
[[ "$http_status" == 201 ]] || {
  cat "$tmp_dir/feed.json" >&2
  fail "authenticated public feed request returned HTTP $http_status"
}

jq -e '.sortSource == "RECOMMENDATION" and (.recommendationResultId | type == "string") and
  (.modelVersion == "feed-rules-v1") and (.items | length > 0)' "$tmp_dir/feed.json" >/dev/null \
  || fail "public response did not prove actual Python recommendation consumption"
result_context=$(jq -er '.resultContextId' "$tmp_dir/feed.json")
response_items=$(jq -c '[.items[].sharedCardId]' "$tmp_dir/feed.json")

persisted_items=$(docker exec "$container" psql -U crabit -d crabit -Atqc \
  "SELECT COALESCE(json_agg(card_id::text ORDER BY position)::text,'[]') FROM behavior_result_item WHERE context_id='${result_context}'")
[[ "$(jq -c . <<<"$persisted_items")" == "$response_items" ]] \
  || fail "persisted behavior positions differ from the public response order"

ranked_items=$(docker exec "$container" psql -U crabit -d crabit -Atqc \
  "SELECT ranked_card_ids::text FROM feed_page_context ORDER BY created_at DESC LIMIT 1")
jq -en --argjson response "$response_items" --argjson ranked "$ranked_items" \
  '($ranked | length) > 0 and
   (([($response | length), ($ranked | length)] | min) as $ranked_count |
    $response[0:$ranked_count] == $ranked[0:$ranked_count])' >/dev/null \
  || fail "public feed order does not preserve the Python-ranked prefix"

echo "feed integration verified: backend=${backend_sha} data=${data_sha} resultContextId=${result_context} items=$(jq 'length' <<<"$response_items")"
