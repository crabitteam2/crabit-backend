# 관계형 export의 엄격한 읽기

`SimulationCommandDispatcher.readRelationalState(byte[])`는 현재 replay DB에서 `relationalState()`로 확보한 카탈로그를 사용해 관계형 state JSON을 읽고 검증한다. 입력에 카탈로그나 SQL을 넣을 수 없으며 DB 연결·쓰기·대상 교체를 수행하지 않는다. 아직 카탈로그를 확보하지 않았거나 replay가 실패한 상태에서는 사용할 수 없다.

입력은 기존 로컬 `state/relational.json` 형식이다. 최상위 필드 5개, version 1, 정확한 38개 테이블과 행 배열을 요구한다. 64 MiB 이하의 엄격한 UTF-8 JSON만 허용하고 BOM, 중복 키, 뒤에 붙은 JSON, 잘못된 Unicode를 기존 bundle parser로 거부한다. dataset ID·catalog digest·칼럼·NULL·PK/unique/FK·가입 인구·BUILDING 상태 검사는 기존 관계형 검증에 연결된다. 승인된 canonical bundle의 cash 전용 `state/export.json` 형식을 바꾸지 않는다.

관계형 검증 전체에 PostgreSQL 값 타입 검사를 추가했다. 현재 migration의 uuid, text/varchar, bool, int4/int8, date, timestamptz, jsonb를 지원하며, 빈 테이블의 칼럼도 지원하지 않는 타입이면 거부한다. UUID는 PostgreSQL export와 같은 소문자 표준 표기만 허용한다. 정수에는 문자열·소수·범위 초과를 허용하지 않는다. 날짜는 유효한 4자리 연도/월/일이며, 시각은 offset이 있는 1~9999년·마이크로초 정밀도를 요구한다. infinity나 반올림이 필요한 시각은 수용하지 않는다. 문자열과 JSON 문자열/키의 NUL도 거부한다. 값이나 원본 JSON을 자동 보정하지 않는다.

이 검사는 완전한 import 계약이 아니다. varchar 길이·모든 CHECK/제품 규칙, JSON 내부 의미, 전체 데이터셋 cutoff·원인 검증은 각 도메인 검증기 및 향후 typed import와 함께 다뤄야 한다. PostgreSQL numeric의 모든 jsonb 수치 한계를 검증한다고 주장하지 않는다. 관계형 읽기 성공은 선택 범위의 closure, 기존 대상의 교체 가능성이나 적용 준비를 뜻하지 않는다. 대상 UUID 변환·백업/revision 바인딩·원자적 apply/restore·동시성 검사는 아직 남아 있다.

`SimulationRelationalInputIT`는 실제 JOIN → GRANT → CREATE → DEPOSIT으로 만든 행을 serialize/read-back하여 동일성을 확인한다. 숫자 문자열·소수·정수 초과·문자열 boolean·잘못된 UUID/날짜/시각·문자열 위치의 객체·NUL JSON을 거부한다. catalog 추가, catalog digest 변조, 비밀키 테이블 삽입, 잘못된 행 컨테이너, 중복/뒤붙인 JSON과 잘못된 UTF-8도 거부한다. SQL처럼 보이는 위시 목적은 일반 문자열로 그대로 유지된다. 검사 전후 DB 지문, 원본 byte 배열과 실제 export가 동일함을 확인한다.

집중 테스트와 전체 회귀의 정확한 실행 상태는 `verification-3719b8.json`, 액션 변경 파일은 `action-files-3719b8.json`에 기록한다. Feature Run/action state, 승인 OpenAPI, canonical schema, main 서비스, migration과 기존 다른 변경은 이번 액션에서 수정하지 않았다. 현재 demo 적용 및 외부 Owner 콘솔 검증도 수행하지 않았다.
