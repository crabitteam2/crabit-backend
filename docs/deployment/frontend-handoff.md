# Frontend and Vercel handoff

Backend environment와 frontend/Vercel cutover는 서로 다른 delivery state다. Staging과 Stable Demo는 독립적으로 다음 pre-cutover gate를 통과해야 한다.

- greenfield PostgreSQL 16에 existing Flyway migration과 matching repository fixture가 적용됨
- running container와 Docker Hub manifest가 selected immutable digest와 일치함
- exact aggregate HTTPS readiness가 `{"status":"UP"}`만 반환함
- matching namespace의 여섯 persona, restart persistence, environment reset behavior가 성공함
- reserved IP, VM, 30/100 GB disk, WIF, OS Login, IAP SSH, READY snapshot, public port boundary가 read-back됨
- browser asset, response, cookie, source map, build output, log에 backend credential/persona token이 노출되지 않음

한 environment gate가 통과한 뒤에만 matching Vercel environment의 absolute HTTPS `BACKEND_URL`을 해당 GCE origin으로 바꾸고 redeploy한다. 이후 Vercel 값, deployment revision/alias, same-origin BFF routing, 여섯 persona와 non-disclosure를 다시 read-back한다. Staging 완료는 Stable Demo 변경을 승인하거나 증명하지 않는다.

Historical database continuity는 없다. frontend contract, `staging/e2e`와 `prod/demo` pair, persona cookie/BFF semantics는 바뀌지 않는다. Rollback은 Google Cloud snapshot/retained digest 안에서만 수행하며 Vercel을 삭제된 이전 hosting provider로 돌리지 않는다.

## #46/#48 행동 수집 전달

프로필 방문 POST, 피드 결과 맥락 POST, 노출/클릭 POST와 내부 지표 계약은 canonical `api/openapi.yaml`에 있다. [행동 수집 문서](../wish/behavior-events.md)의 화면 진입/50%·1000ms 가시성 주기/인증 actor/replay/오류 중단 규칙을 #95/#96에서 구현한다. 이 백엔드 PR은 프런트엔드 계약 복사, 타입 재생성, 실제 브라우저 계측을 완료했다고 주장하지 않는다. 내부 지표는 학생 토큰으로 호출하지 않는다.
