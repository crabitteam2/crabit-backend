# Google Cloud provisioning and host bootstrap

`deploy/google-cloud/plan.json`은 두 환경의 고정된 non-secret architecture다. region/zone은 `asia-northeast3`/`asia-northeast3-a`, VM은 `e2-medium`, boot/data disk는 30/100 GB, data disk type은 `pd-balanced`다. `verify-plan.sh`는 zone, port, identity, disk, budget, snapshot, environment 격리 drift를 deterministic하게 거부한다.

## Project provisioning

1. `deploy/google-cloud/project.env.example`을 저장소 밖 mode 0600 파일로 복사하고 project number, billing account, Monitoring notification channel을 입력한다.
2. operator identity로 필요한 Google API와 billing/project 연결을 확인한다.
3. 환경 파일을 source한 뒤 `scripts/deployment/google-cloud/provision.sh`를 실행한다.
4. script의 `verify-project.sh` read-back이 두 VM, reserved IP, environment-bound WIF, resource-scoped OS Login/IAP, public 80/443, 30/100 GB disk, single-writer attachment, daily snapshot policy, billing-account link, exact-project USD 200 budget과 USD 100/150/180 alert를 모두 확인해야 한다.

Provisioning은 environment별 deployer/runtime service account, immutable repository ID와 environment claim에 묶인 WIF provider, public HTTPS firewall, IAP source range `35.235.240.0/20`의 SSH firewall, address, disk, snapshot schedule, instance를 만든다. 각 deployer의 변경 권한은 matching VM, data disk, snapshot name prefix, runtime service account, IAP target으로 제한된다. shared network는 read-only다. Budget filter는 exact `projects/<GCP_PROJECT_NUMBER>` 하나이며 project의 billing account가 `GCP_BILLING_ACCOUNT`와 같아야 한다. Budget alert는 notification이며 hard spending cap이 아니다.

## Host bootstrap

각 VM에서 OS Login/IAP로 `deploy/google-cloud/bootstrap-host.sh`를 한 번 실행한다. exact attached device `/dev/disk/by-id/google-crabit-data`만 초기화하고 UUID로 `/mnt/disks/crabit-data`에 mount한다. Docker data-root를 그 disk 아래로 옮겨 PostgreSQL named volume을 persistent disk에 둔다. 이미 filesystem이 있는 disk는 재format하지 않는다.

부트스트랩 뒤 다음을 read-back한다.

- Docker root가 `/mnt/disks/crabit-data/docker`이고 data disk UUID가 mount되어 있다.
- 현재 OS Login principal이 `docker` group을 통해 새 login session에서 Docker를 실행할 수 있다.
- `~/.local/share/crabit/releases`, `~/.local/state/crabit`, `~/.config/crabit`의 mode가 0700이다.
- verified instance host key는 `gce-<instance>` alias로 GitHub environment의 `CRABIT_GCP_KNOWN_HOSTS`에 고정한다. workflow에서 host key를 발견하지 않는다.

Bootstrap 완료와 live service 준비 완료는 별도 상태다. 아직 application image나 Vercel 값은 변경하지 않는다.
