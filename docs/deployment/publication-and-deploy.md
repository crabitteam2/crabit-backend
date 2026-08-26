# Publication and deployment

## Branch matrix

1. PR은 focused tests, 전체 suite, image/runtime/workflow/Google Cloud plan 검증을 통과한다.
2. `develop` 또는 `main`의 exact commit은 single-platform `sha-<commit12>` image 하나를 Docker Hub에 발행하고 registry digest와 locally tested config digest를 exact read-back한다. mutable selector나 multi-platform index를 배포하지 않는다.
3. `develop` digest는 `Deploy Staging` 수동 workflow의 입력이다. image OCI revision이 current `origin/develop`의 ancestor일 때만 진행한다.
4. `main` publication run은 같은 job output digest를 Stable Demo deployment job에 직접 전달한다.
5. deployment job은 GitHub OIDC를 environment deployer service account로 교환하고 `verify-environment.sh`로 reserved address, host, VM, OS Login, disk와 single writer를 read-back한다.
6. exact operation ID로 data-disk snapshot을 만들고 status `READY`, source disk, 100 GB size, labels, provider ID를 확인한다. 실패한 create는 같은 이름을 authoritative read-back해 exact match인 경우만 채택한다.
7. runtime file, snapshot proof, archive를 pinned host-key/IAP transport로 전송한다. 전송 전에 selected environment, project, zone, instance, data disk, snapshot proof, HostKeyAlias, active deployer, provider가 read-back한 destination VM identity가 모두 일치해야 한다. remote deployment는 operation lock 안에서 snapshot proof를 다시 검증한다.

## Runtime read-back

Remote script는 exact digest를 pull하고 PostgreSQL, backend, Caddy를 시작한 뒤 다음을 확인한다.

- running container `.Config.Image`와 local RepoDigests가 selected immutable digest와 같다.
- public HTTPS readiness가 성공 HTTP와 정확히 `{"status":"UP"}`만 반환한다.
- 성공한 digest만 current state에 원자적으로 기록하고 이전 verified digest를 rollback candidate로 보존한다.
- backend 8080과 PostgreSQL 5432는 host port를 publish하지 않는다.

Workflow success도 현재 Google Cloud/Vercel delivery success의 충분조건이 아니다. workflow run, Docker Hub digest, snapshot, VM running digest, disk attachment, firewall, HTTPS response를 별도 read-back한다.
