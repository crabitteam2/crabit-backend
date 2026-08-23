#!/usr/bin/env bash
set -Eeuo pipefail

[[ "$#" == "2" ]] || { printf 'usage: verify-image.sh <image> <40-char-revision>\n' >&2; exit 2; }
readonly IMAGE="$1"
readonly EXPECTED_REVISION="$2"
[[ "${EXPECTED_REVISION}" =~ ^[0-9a-f]{40}$ ]] || { printf 'invalid expected revision\n' >&2; exit 2; }

user="$(docker image inspect "${IMAGE}" --format '{{.Config.User}}')"
[[ "${user}" =~ ^[1-9][0-9]*(:[1-9][0-9]*)?$ ]] || { printf 'image user is not numeric non-root\n' >&2; exit 1; }
[[ "$(docker image inspect "${IMAGE}" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')" == "${EXPECTED_REVISION}" ]] \
	|| { printf 'OCI revision label mismatch\n' >&2; exit 1; }
[[ "$(docker image inspect "${IMAGE}" --format '{{index .Config.Labels "org.opencontainers.image.source"}}')" == "https://github.com/crabitteam2/crabit-backend" ]] \
	|| { printf 'OCI source label mismatch\n' >&2; exit 1; }
docker image inspect "${IMAGE}" --format '{{json .Config.ExposedPorts}}' \
	| jq -e 'keys == ["8080/tcp"]' >/dev/null \
	|| { printf 'image must expose only 8080/tcp\n' >&2; exit 1; }
docker image inspect "${IMAGE}" --format '{{json .Config.Env}}' \
	| jq -e 'all(.[]; test("CRABIT_(DATABASE|DEMO_TOKEN)") | not)' >/dev/null \
	|| { printf 'image config contains a credential-bearing environment key\n' >&2; exit 1; }

tmp_dir="$(mktemp -d)"
container_id=""
cleanup() {
	if [[ -n "${container_id}" ]]; then docker rm -f "${container_id}" >/dev/null 2>&1 || true; fi
	rm -rf "${tmp_dir}"
}
trap cleanup EXIT
container_id="$(docker create "${IMAGE}")"
docker export "${container_id}" --output "${tmp_dir}/rootfs.tar"
if tar -tf "${tmp_dir}/rootfs.tar" | awk '
	/(^|\/)(\.git|\.gradle)(\/|$)|\.java$|(^|\/)\.env($|\.)|\.key$/ { found = 1 }
	/\.pem$/ &&
		$0 !~ /^etc\/ssl\/certs\/ca-cert-.*\.pem$/ &&
		$0 != "etc/ssl/cert.pem" &&
		$0 != "etc/ssl1.1/cert.pem" &&
		$0 != "usr/share/gnupg/sks-keyservers.netCA.pem" { found = 1 }
	END { exit(found ? 0 : 1) }
'; then
	printf 'runtime filesystem contains forbidden source, cache, env, or key material\n' >&2
	exit 1
fi

printf 'image verified: %s revision=%s user=%s\n' "${IMAGE}" "${EXPECTED_REVISION}" "${user}"
