# Flyway 자동 적용 차단·로컬 DB 앱 계정 분리

- 작업일: 2026-10-23
- 범위: 로컬 앱 설정과 로컬 `rag_db` 접속 권한. Spark DB/계정은 미변경.
- 승인: 태환님이 자동 적용 차단·DB 권한 분리 및 로컬 `.env` 앱 자격 증명 전환을 요청함.

## 변경

- `application.yml`: `spring.flyway.enabled=false`를 기본값으로 설정. 일반 앱 기동 및 Spring 테스트에서 Flyway 자동 migration을 사용하지 않는다.
- 앱 DataSource를 `DB_APP_USERNAME`/`DB_APP_PASSWORD`로 분리. 기존 `DB_PASSWORD`는 docker-compose 관리자 계정용으로 보존한다.
- 로컬 PostgreSQL에 `rag_app` LOGIN 계정 생성. 슈퍼유저·DB 생성·역할 생성·schema CREATE·기존 `rag` 역할 상속 권한 없음. 기존 네 개 앱 테이블에는 SELECT/INSERT/UPDATE/DELETE, 해당 시퀀스에는 USAGE/SELECT만 부여. `flyway_schema_history`에는 앱 권한을 부여하지 않음.
- 로컬 `.env`에 앱 전용 계정과 생성 암호를 기록하고 권한을 0600으로 변경. 자격 증명 값은 코드·문서·로그에 남기지 않는다. 기존 DB 관리자 접속 정보와 데이터/스키마는 변경하지 않았다.
- `.env.example`, Spark 환경 예시, README, 배포 문서에 분리된 접속 정보와 배포 선행 조건을 반영했다.

## 실제 검증

- YAML 파싱: `spring.flyway.enabled=false`; DataSource가 앱 전용 변수만 참조함.
- `./gradlew --no-daemon compileJava compileTestJava processResources`: BUILD SUCCESSFUL.
- `./gradlew --no-daemon test --tests com.nohtaehwan.rag.HealthControllerTest`: BUILD SUCCESSFUL (DB mapper mock 사용; 전체 suite 아님).
- 로컬 DB TCP에서 `rag_app` 로그인 성공. 조회 결과: `rolsuper=false`, `has_schema_privilege(public, CREATE)=false`, `has_database_privilege(rag_db, CREATE)=false`, `has_table_privilege(tb_document, INSERT)=true`, `pg_has_role(rag_app, rag, MEMBER)=false`.
- localhost 임의 포트에서 임시 `bootRun` 실행 후 `GET /health` → `{"database":"UP","status":"UP"}`. 검증 프로세스는 종료했다.
- 검증 전후 읽기 전용 조회: Flyway 적용 이력 V1~V3 성공, 최종 문서 101건·청크 404건. 단, 적용 전 시점의 신뢰할 수 있는 기준 건수와 비교한 데이터 무결성 검증은 아니다.
- `git diff --check`: 통과. commit/push 없음.
- `git check-ignore -v .env`: `.gitignore`에 의해 제외됨.
- 임시 `hermes-verify-*.py`(OS 임시 디렉터리) ad-hoc 검사: Flyway 비활성화, 앱 전용 환경변수·예시 파일, `.env` 권한, 로컬 DB 역할의 DDL/DML 권한, `git diff --check` 모두 PASS. 실행 후 임시 스크립트 삭제. 이는 전체 테스트 통과가 아니다.

## 미실행·주의

- 전체 `./gradlew test`와 DB 통합 테스트: 이번 변경 후에는 미실행. 기존 로컬 DB를 대상으로 돌리면 테스트 정리 SQL이 데이터를 변경할 수 있어 실행하지 않았다. 테스트 성공으로 표기하지 않는다.
- Claude Stop Hook 자체는 수정하지 않았다. 현재 프로젝트 정책상 DB 통합 테스트는 별도 격리 DB에서만 실행해야 한다.
- 앱 역할에는 DML 권한이 있으므로 이 구성은 **DDL 방지**이지 테스트의 데이터 삭제 방지가 아니다. DB 테스트에는 별도 격리 DB가 계속 필요하다.
- 미래 migration이 만드는 테이블/시퀀스에는 앱 권한을 자동 부여하지 않았다. 태환님이 migration을 별도 승인·적용할 때 필요한 최소 권한을 함께 검토해야 한다.
- Spark에는 새 앱 역할/자격 증명/V3를 적용하지 않았다. 현재 변경 코드를 Spark에 그대로 배포하면 앱 자격 증명 부재로 기동하지 않는다. 기존 배포 검증 이력을 현재 변경의 검증으로 오인하지 않는다.
- 앞서 Codex가 지적한 Part 1 V3의 사용자–KB 소유 관계 및 테스트 정리 SQL High 위험은 이 작업의 수정 범위 밖이며 여전히 미해결이다.
