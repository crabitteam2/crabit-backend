# 테스트 커버리지 리포트

이 프로젝트는 Gradle의 JaCoCo 플러그인으로 `src/main/java` 전체의 테스트 커버리지를 측정한다. 별도의 최소 커버리지 기준은 적용하지 않으며, 리포트 수치가 빌드 성공 여부를 바꾸지 않는다.

## 실행 환경

- JDK 21
- 실행 중인 Docker 엔진

전체 테스트에는 Testcontainers 기반 PostgreSQL 통합·E2E 테스트가 포함되므로 Docker를 사용할 수 있어야 한다.

## 전체 테스트와 리포트

```shell
./gradlew clean test --console=plain
```

`test`가 끝나면 `jacocoTestReport`가 자동으로 실행되어 전체 테스트 실행분의 HTML/XML 리포트를 만든다. 테스트가 실행된 뒤 실패하더라도 리포트는 finalizer로 생성되며, Gradle 명령은 원래 테스트 실패 상태를 그대로 반환한다.

리포트 태스크를 직접 실행할 수도 있다. 이 경우 `test`가 먼저 실행된다.

```shell
./gradlew jacocoTestReport --console=plain
```

## 일부 테스트만 측정

필터링한 테스트만 다시 실행하면 JaCoCo 실행 데이터와 리포트도 해당 실행분으로 갱신된다. 예를 들어 다음 명령은 `MoneyValueTest`가 실행한 코드만 반영한다.

```shell
./gradlew test --tests '*MoneyValueTest' --rerun-tasks --console=plain
```

부분 실행 후의 리포트를 전체 테스트 커버리지로 해석하지 않는다. 전체 수치가 필요하면 `clean test`를 다시 실행한다.

## 리포트 위치

- HTML: `build/reports/jacoco/test/html/index.html`
- XML: `build/reports/jacoco/test/jacocoTestReport.xml`

`build/`는 Git에서 무시되므로 생성된 실행 데이터와 리포트는 커밋하지 않는다.
