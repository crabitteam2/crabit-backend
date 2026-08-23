# Vultr host bootstrap

Staging과 Stable Demo를 서로 다른 Seoul(`icn`) 2 GB instance에 한 번씩 구성한다. 이 절차는 자동 provisioning이 아니며 host 생성·방화벽·IP·key read-back은 별도 controller-bound 공급자 작업이다.

1. Ubuntu 24.04 LTS host와 비-root `deploy` 사용자를 만든다.
2. Docker Engine, Compose plugin, `curl`, `jq`, `flock`을 설치한다. `deploy` 사용자에게 필요한 최소 Docker 실행 권한만 부여한다.
3. 전용 ed25519 deploy key를 설치하고 개인 key나 root SSH를 사용하지 않는다. host key는 trusted out-of-band channel에서 확인해 GitHub environment secret에 고정한다.
4. Vultr firewall과 host firewall은 관리용 SSH와 TCP 80/443만 허용한다. backend 8080과 PostgreSQL 5432를 host에 publish하지 않는다.
5. `~/.local/share/crabit/releases`와 `~/.local/state/crabit`은 mode 0700으로 만든다. runtime env와 image state는 mode 0600을 유지한다.
6. Staging과 Stable Demo에 서로 다른 database 이름, password, Compose project, volume 이름을 쓴다. `docker compose down -v`는 운영 host에서 금지한다.

Caddy는 `api-staging.<public-ip>.sslip.io` 또는 `api-demo.<public-ip>.sslip.io`처럼 현재 public IP를 포함한 host를 사용한다. instance IP가 바뀌면 HTTPS read-back 후 Vercel `BACKEND_URL`도 별도 갱신한다.
