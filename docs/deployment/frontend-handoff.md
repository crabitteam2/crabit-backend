# Frontend and Vercel handoff

백엔드 배포와 frontend/Vercel 준비는 서로 다른 delivery 상태다. backend가 준비되면 frontend owner에게 값이 아니라 다음 verified metadata만 전달한다.

- Staging과 Stable Demo의 HTTPS origin
- 각 origin의 readiness read-back 시각과 running image digest
- Stable Demo가 기대하는 여섯 server-only credential 이름
- public IP가 바뀌면 `sslip.io` origin과 Vercel `BACKEND_URL`을 함께 바꿔야 한다는 조건

Vercel에는 환경별 absolute HTTPS `BACKEND_URL`을 설정한다. browser credential을 backend로 전달하지 않고 frontend BFF가 HttpOnly persona cookie를 server-side token으로 바꾼다. token 값은 browser bundle, cookie, response, log, repository, 이 handoff 문서에 넣지 않는다.

frontend persona/BFF 변경, Vercel environment 설정, deployment alias, end-to-end persona 사용 가능 여부는 frontend owner와 Vercel의 별도 authoritative read-back이 있어야 완료다.
