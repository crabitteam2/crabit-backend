# Staging 피드 런타임

피드는 기본 비활성이다. Staging GitHub Environment의 `CRABIT_FEED_RANKING_ENABLED` 변수를 `true`로 설정할 때만 배포 스크립트가 내부 `feed` 서비스를 시작한다. `CRABIT_FEED_CLASSIFIER_VERSION` 변수와 별도 `CRABIT_FEED_RANKING_CREDENTIAL` secret을 함께 준비해야 한다. credential은 recap credential과 달라야 하며 환경파일에 안전한 영문/숫자 및 `._:/@+-`만 허용한다. 실제 secret 값은 문서, 로그 및 release state에 기록하지 않는다.

기존 `CRABIT_RECAP_IMAGE` 불변 digest를 feed에도 재사용한다. 이미지에는 `feed_service.wsgi:application`, `feed_service.validation` 및 `wish_category_classifier.py`가 있어야 한다. Gunicorn은 기존 `CRABIT_RECAP_HOST`/`CRABIT_RECAP_PORT` 설정으로 8081에 바인딩한다. Feed는 공개 포트나 DB credential 없이 내부 recap 네트워크에서만 실행된다. 백엔드는 `http://feed:8081/internal/v1/feed-rankings`를 호출한다.

`bash scripts/deployment/verify-feed-runtime.sh BACKEND_IMAGE DATA_IMAGE [CLASSIFIER_VERSION]`는 선택한 백엔드 이미지의 `/app/app.jar`에서 실제 `FeedClassifierV1.class` 상수 풀의 gzip artifact를 추출한다. artifact 자체의 SHA-256, 클래스에 선언된 digest, 설정 version, 선택한 Python 이미지의 classifier source SHA-256 및 category 집합이 일치해야 한다. 백엔드 소스 checkout이나 이미지에 존재하지 않는 JSON 파일을 대신 검증하지 않는다. 임베딩 표현이 달라지면 검증도 실패하도록 설계되어 있다. 성공 출력의 `wish-category-v1@sha256:...` 값을 Environment 변수에 사용한다.

동일 검증은 임시 Python 컨테이너에 테스트용 credential을 주고 실제 HTTP로 `/health`, 인증된 빈 후보 ranking, 요청 digest, 잘못된 credential의 401, 잘못된 classifier 형식의 422를 확인한다. Python API 자체는 유효한 형식의 다른 classifier digest를 거부하지 않으므로 digest 불일치는 배포 사전 검증에서 차단한다. 런타임 이미지에는 sklearn/numpy가 없으므로 Python 분류기의 재학습/재계산은 수행하지 않는다. 이 검증은 이미지 호환성과 Python HTTP 동작의 증거이며, 실제 Staging 백엔드의 피드 화면 호출이나 원격 배포 완료 증거는 아니다.

배포는 이미지 사전 검증 후 recap과 feed readiness를 확인하고 백엔드를 시작한다. 최종 HTTPS readiness 이후에도 feed health 및 설정 이미지 일치를 다시 확인한다. 실패 시 검증된 기존 릴리스를 복원한다. 최초 실패는 feed를 포함한 부분 serving 컨테이너를 정지한다. enabled → disabled 전환은 feed 컨테이너를 제거한다. DB 볼륨은 삭제하지 않는다.

`current-release.env`와 `previous-release.env`에는 두 이미지와 feed opt-in, 활성 시 classifier version만 저장한다. 기존 두 이미지 형식은 feed=false로 해석한다. `rollback.sh`는 인자로 지정한 두 digest가 previous release와 일치해야 하며 그 릴리스의 비밀 없는 설정을 복원한다. 활성 릴리스 복구에는 현재 runtime.env의 유효한 전용 credential이 필요하다. 비밀 변경 이력은 보관하지 않으므로 이전 secret 복원은 지원하지 않는다. 롤백 시 DB migration 호환성 확인 인자는 기존대로 필요하다.

`verify-feed-regressions.sh`는 가짜 Docker/HTTPS 응답으로 실제 배포 스크립트의 최초 실패, feed health/최종 health/이미지 불일치 복구, 환경변수 오염 방어, legacy rollback, release secret 배제를 검사한다. `verify-workflows.sh`와 Google Cloud regressions 및 Gradle 테스트는 기존 경로를 검증한다. Backend CI는 실제 선택 이미지로 Python HTTP 검증을 수행한다. 작업별 실행 여부와 결과는 PR 검증 기록을 따른다.

이 변경은 secret 등록, 이미지 게시, workflow dispatch, VM 배포, merge/release를 수행하지 않는다. 운영자가 별도 승인 후 호환 불변 digest와 Environment 설정을 준비하고 기존 Staging 배포 절차를 실행해야 한다. Stable Demo는 opt-in 입력을 추가하지 않아 기본 비활성 경로를 유지한다.
