#!/usr/bin/env bash
set -Eeuo pipefail

readonly data_device="/dev/disk/by-id/google-crabit-data"
readonly data_mount="/mnt/disks/crabit-data"

[[ "$(id -u)" != "0" ]] || { printf 'run through an OS Login user, not root\n' >&2; exit 1; }
for command in blkid findmnt lsblk; do
	command -v "${command}" >/dev/null 2>&1 || { printf 'missing command: %s\n' "${command}" >&2; exit 1; }
done
[[ -b "${data_device}" ]] || { printf 'expected attached data disk is absent: %s\n' "${data_device}" >&2; exit 1; }

if ! sudo blkid "${data_device}" >/dev/null 2>&1; then
	sudo mkfs.ext4 -m 0 -F -E lazy_itable_init=0,lazy_journal_init=0,discard "${data_device}"
fi
uuid="$(sudo blkid -s UUID -o value "${data_device}")"
[[ "${uuid}" =~ ^[0-9a-f-]{36}$ ]] || { printf 'data disk UUID read-back failed\n' >&2; exit 1; }

sudo install -d -m 0755 "${data_mount}"
fstab_line="UUID=${uuid} ${data_mount} ext4 discard,defaults,nofail 0 2"
if ! grep -Fqx "${fstab_line}" /etc/fstab; then
	printf '%s\n' "${fstab_line}" | sudo tee -a /etc/fstab >/dev/null
fi
sudo mount "${data_mount}" 2>/dev/null || true
[[ "$(findmnt -n -o UUID --target "${data_mount}")" == "${uuid}" ]] \
	|| { printf 'data disk mount read-back failed\n' >&2; exit 1; }

sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y ca-certificates curl jq
sudo install -m 0755 -d /etc/apt/keyrings
sudo curl --fail --silent --show-error --location \
	https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
sudo chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
printf '%s\n' \
	"deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
	| sudo tee /etc/apt/sources.list.d/docker.list >/dev/null
sudo apt-get update
sudo DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

sudo install -d -m 0711 "${data_mount}/docker"
sudo install -d -m 0755 /etc/docker
printf '%s\n' '{"data-root":"/mnt/disks/crabit-data/docker","log-driver":"local"}' \
	| sudo tee /etc/docker/daemon.json >/dev/null
sudo usermod -aG docker "$(id -un)"
sudo systemctl enable --now docker
[[ "$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || sudo docker info --format '{{.DockerRootDir}}')" == "${data_mount}/docker" ]] \
	|| { printf 'Docker data-root read-back failed\n' >&2; exit 1; }

install -d -m 0700 "${HOME}/.local/share/crabit/releases" "${HOME}/.local/state/crabit" "${HOME}/.config/crabit"
printf 'host bootstrap verified: device=%s mount=%s docker-root=%s\n' \
	"${data_device}" "${data_mount}" "${data_mount}/docker"
