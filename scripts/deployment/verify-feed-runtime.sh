#!/usr/bin/env bash
set -Eeuo pipefail

# Both arguments identify the actual selected images, never a source checkout.
[[ "$#" == 2 || "$#" == 3 ]] || { echo 'usage: verify-feed-runtime.sh <backend-image> <data-image> [classifier-version]' >&2; exit 2; }
backend_image="$1"
data_image="$2"
expected_version="${3:-}"
temporary_directory="$(mktemp -d)"
backend_container=""
feed_container=""
cleanup() {
    [[ -z "${backend_container}" ]] || docker rm -f "${backend_container}" >/dev/null 2>&1 || true
    [[ -z "${feed_container}" ]] || docker rm -f "${feed_container}" >/dev/null 2>&1 || true
    rm -rf "${temporary_directory}"
}
trap cleanup EXIT
backend_container="$(docker create "${backend_image}")"
docker cp "${backend_container}:/app/app.jar" "${temporary_directory}/app.jar"
chmod 755 "${temporary_directory}"
chmod 644 "${temporary_directory}/app.jar"
version="$(docker run --rm -i --network none --read-only --entrypoint python \
    -v "${temporary_directory}/app.jar:/verify/app.jar:ro" "${data_image}" - "${expected_version}" <<'PY'
import base64, gzip, hashlib, json, pathlib, re, struct, sys, zipfile
from feed_service.validation import CATEGORIES
# Parse the class constant pool rather than assuming that the image ships source/JSON.
with zipfile.ZipFile('/verify/app.jar') as jar:
    raw = jar.read('BOOT-INF/classes/com/crabit/backend/recommendation/FeedClassifierV1.class')
assert raw[:4] == b'\xca\xfe\xba\xbe', 'Invalid classifier class'
count = struct.unpack_from('>H', raw, 8)[0]
offset, index, strings = 10, 1, []
widths = {3:4, 4:4, 5:8, 6:8, 7:2, 8:2, 9:4, 10:4, 11:4, 12:4, 15:3, 16:2, 17:4, 18:4, 19:2, 20:2}
while index < count:
    tag = raw[offset]; offset += 1
    if tag == 1:
        length = struct.unpack_from('>H', raw, offset)[0]; offset += 2
        strings.append(raw[offset:offset + length]); offset += length
    else:
        assert tag in widths, 'Unsupported class constant'
        offset += widths[tag]
        if tag in (5, 6): index += 1
    index += 1
# Exporter emits ordered, long base64 chunks. Fail closed if that representation changes.
chunks = [s for s in strings if len(s) > 512 and re.fullmatch(rb'[A-Za-z0-9+/=]+', s)]
assert chunks and chunks[0].startswith(b'H4sI'), 'Unsupported classifier embedding'
artifact_bytes = gzip.decompress(base64.b64decode(b''.join(chunks), validate=True))
digest = hashlib.sha256(artifact_bytes).hexdigest()
assert digest.encode() in strings, 'Embedded classifier digest mismatch'
artifact = json.loads(artifact_bytes)
assert artifact['schema_version'] == 1 and artifact['algorithm'] == 'tfidf-char-wb-2-3-v1', 'Unsupported classifier'
assert artifact['source_sha256'] == hashlib.sha256(pathlib.Path('/app/wish_category_classifier.py').read_bytes()).hexdigest(), 'Selected Python image classifier source mismatch'
assert set(artifact['categories']) == CATEGORIES, 'Selected Python image category mismatch'
version = 'wish-category-v1@sha256:' + digest
assert not sys.argv[1] or sys.argv[1] == version, 'Configured classifier version does not match selected images'
print(version)
PY
)"
# This synthetic credential is used only in the disposable verification container.
feed_container="$(docker run -d --read-only --tmpfs /tmp:size=64m,mode=1777 \
    -e FEED_RANKING_CREDENTIAL=feed-runtime-verification-only \
    -e CRABIT_RECAP_HOST=0.0.0.0 -e CRABIT_RECAP_PORT=8081 \
    "${data_image}" --config /app/gunicorn.conf.py feed_service.wsgi:application)"
docker exec -i "${feed_container}" python - "${version}" <<'PY'
import hashlib, json, sys, time, urllib.error, urllib.request
base = 'http://127.0.0.1:8081'
for attempt in range(60):
    try:
        with urllib.request.urlopen(base + '/health', timeout=2) as response:
            assert json.load(response) == {'status':'ok'}
        break
    except (OSError, AssertionError):
        if attempt == 59: raise
        time.sleep(1)
metrics = {'month':'2026-02','coverage':'COMPLETE','metrics_version':'core-metrics-v1','values':{'deposit_count':0,'total_savings':0,'avg_amount':0,'regularity_std':None,'pace_bias':None,'abandon_count':0,'transfer_count':0,'visit_count':0}}
payload = {'schema_version':1,'request_id':'11111111-1111-4111-8111-111111111111','context_id':'22222222-2222-4222-8222-222222222222','viewer_id':'33333333-3333-4333-8333-333333333333','academy_id':'44444444-4444-4444-8444-444444444444','recommendation_at':'2026-03-10T03:00:00Z','timezone':'Asia/Seoul','feature_version':'feed-features-v1','classifier_version':sys.argv[1],'viewer_previous_month':metrics,'candidates':[]}
def call(value, token):
    body = json.dumps(value, separators=(',',':')).encode()
    request = urllib.request.Request(base + '/internal/v1/feed-rankings', data=body, headers={'Content-Type':'application/json','Authorization':'Bearer '+token})
    try:
        with urllib.request.urlopen(request, timeout=3) as response: return response.status, json.load(response), body
    except urllib.error.HTTPError as error: return error.code, json.load(error), body
status, result, raw = call(payload, 'feed-runtime-verification-only')
assert status == 200 and result['ordered_card_ids'] == []
assert result['request_id'] == payload['request_id'] and result['context_id'] == payload['context_id']
assert result['input_digest'] == 'sha256:' + hashlib.sha256(raw).hexdigest()
assert call(payload, 'incorrect-verification-credential')[0] == 401
payload['classifier_version'] = 'invalid'
assert call(payload, 'feed-runtime-verification-only')[0] == 422
print('feed HTTP verified: readiness, authenticated ranking, input digest, auth rejection, classifier format rejection')
PY
printf 'feed selected-image compatibility verified: %s\n' "${version}"
