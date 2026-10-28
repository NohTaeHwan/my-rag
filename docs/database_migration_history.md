# 데이터베이스 Migration 이력

Flyway migration 원본은 `src/main/resources/db/migration/`에서 관리한다. 앱 설정은 `spring.flyway.enabled=false`가 기본이므로 일반 앱 기동으로 migration을 자동 적용하지 않는다. 실제 스키마 변경은 검토·승인 후 관리자 계정으로 수행한다.

## V1–V4 요약

| 버전 | 변경 내용 |
|---|---|
| V1 | `vector` 확장을 사용하고 `tb_document`, `tb_document_chunk` 및 문서–Chunk FK/인덱스를 만든다. Embedding 차원은 1024다. |
| V2 | `tb_document.source`에 전역 UNIQUE 제약을 추가한다. 기존 중복 source가 있으면 migration이 실패하며 데이터를 자동 삭제하지 않는다. |
| V3 | `tb_user`, `tb_knowledge_base`를 추가하고 `tb_document`를 사용자·KB 범위로 확장한다. 기존 문서는 개발용 사용자/기본 KB에 귀속한다. 전역 `source` UNIQUE를 `(user_id, knowledge_base_id, source)` UNIQUE로 교체한다. |
| V4 | 문서의 `user_id`와 참조 KB의 소유자가 항상 일치하도록 복합 FK를 추가한다. 적용 전 불일치 행이 있으면 migration 전체를 실패시키며 데이터를 자동 수정·삭제하지 않는다. |

세부 DDL은 각 버전의 Flyway SQL 파일을 기준으로 한다. 특히 V3 적용 후 검색 SQL의 사용자·KB 범위 제한은 별도 구현 항목이며, DB 제약만으로 검색 경로가 자동 격리되는 것은 아니다.

## 로컬 DB 적용 상태

- 대상: `rag_db`
- Flyway 이력: V1 baseline, V2, V3, V4 모두 성공.
- V4 기록: `success=true`, `installed_on=2026-10-27 16:27:29.231521`.
- 사후 확인: `current_database()`가 `rag_db`; `fk_document_knowledge_base_owner` 제약 존재.
- V4는 격리 DB에서 migration 및 저장·검색 통합 테스트를 통과한 후 사용자가 로컬 DB에 적용했다. 실제 적용은 사용자가 수행했으며, 이 문서화 작업에서 DB를 변경하지 않았다.

## 검증 범위와 한계

- `isolatedMigrationTest`: 격리 PostgreSQL에서 V3 4건, V4 4건 통과.
- `isolatedIntegrationTest`: 별도 격리 PostgreSQL에서 문서 저장 7건, 검색 3건 통과. V1–V4 자동 적용 확인.
- `IsolatedDatabaseGuardTest`: DB 비연결 10건 통과.
- 전체 기본 `./gradlew test`는 일반 `Test` task의 `.env` 주입 경로가 남아 있어 실행하지 않았다. 따라서 전체 테스트 suite 통과를 의미하지 않는다.

## DB 계정 분리

- `DB_USERNAME=rag` / `DB_PASSWORD`: PostgreSQL 관리자 계정. 관리 작업 및 migration에 사용한다.
- `DB_APP_USERNAME=rag_app` / `DB_APP_PASSWORD`: 앱 실행 계정. 관리자 계정과 별도이며 앱 접속용이다.
- 두 계정의 비밀번호를 혼용하지 않는다. 비밀번호 값은 이 문서나 로그에 기록하지 않는다.
- V4 적용 후 임시 `SPRING_FLYWAY_*` 설정은 제거하고, 기본 Flyway 자동 적용 비활성 상태를 유지한다.
