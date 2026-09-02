# 개발 작업별 claude-kit 준수 체크리스트

## 작업 정보

- 작업명: RAG MVP 3단계 — BGE-M3 Embedding 연동 (EmbeddingClient)
- 작업 일자: 2026-09-02
- 관련 문서: `docs/rag_mvp_development_plan.md`(3단계), `docs/local_embedding_model_setup_dgx_spark.md`
- 담당: nohtaehwan (Claude Code 협업)

## 1. 작업 시작 전

- [x] 요청 범위와 완료 기준을 정리했다. — 새 REST 엔드포인트 없이 `EmbeddingClient`로 API 호출·벡터 차원 검증까지
- [x] 요구사항이 모호한 부분을 먼저 확인했다. — base-url `/v1` 포함 여부, 인증 필요 여부를 curl로 직접 검증 후 확정
- [x] `CLAUDE.md`를 읽었다. — 세션 중 변경분(claude-kit 규칙 추가) 포함 재독
- [x] 참고 문서 이정표에서 관련 문서를 읽었다. — `local_embedding_model_setup_dgx_spark.md`, `rag_backend_developer.md`, `rag_mvp_development_plan.md`
- [x] 변경 대상의 기존 구조와 사용처를 확인했다. — 기존 `embedding` 관련 코드 없음, 신규 패키지로 시작
- [ ] 신규 API면 `spring-api-create` 등 Skill을 확인했다. — 해당 없음 (신규 REST 엔드포인트 아님, 내부 컴포넌트만 추가)

## 2. 설계·구현

- [x] 기존 Controller → Service → 데이터 접근 구조를 유지했다. — 이번 작업은 Retrieval/Answer 이전 단계로 Controller 없음
- [x] 프로젝트의 Java·Spring Boot·Gradle 버전을 따랐다. — Java 17, Spring Boot 3.5.6
- [x] 프로젝트에서 사용하지 않는 JPA·MyBatis·WebFlux·Swagger/OpenAPI를 임의로 추가하지 않았다.
- [x] 클래스·메서드 역할과 필요한 파라미터·예외 주석을 작성했다.
- [x] Service 레이어의 주요 흐름과 경고 로그를 작성했다. — `@Slf4j`로 호출 실패·차원 불일치 로그
- [x] 비즈니스 예외가 있으면 프로젝트의 단일 예외 체계를 사용했다. — `RagException` 신규 도입 (프로젝트 최초 예외 클래스)
- [x] 민감 정보와 자격 증명을 코드·설정·로그에 기록하지 않았다. — 실제 Embedding base-url은 `.env`(git 미추적)에만 저장, `application.yml` 커밋값은 로컬 더미

## 3. 테스트·검증

- [x] 변경 계층에 대응하는 테스트를 작성하거나 수정했다. — `EmbeddingClientTest` (성공/API 실패/차원 불일치 3케이스, `MockRestServiceServer`)
- [x] `./gradlew test`를 실행했다. — 4개 테스트 모두 통과
- [x] 테스트 결과를 실제 출력으로 확인했다. — JUnit XML 결과 `tests="3" failures="0"` 등 확인
- [x] 필요하면 Health Check·DB·외부 호출 등 변경된 경계를 별도로 검증했다. — 실제 DGX Spark BGE-M3 서버(`100.90.113.121:8003`)에 curl 및 임시 테스트로 실제 호출, 벡터 차원 1024 확인 후 임시 테스트 파일 삭제
- [ ] claude-kit Stop Hook의 컴파일·대응 테스트 결과를 확인했다. — 세션 내 Stop Hook 실행 로그 미확인 (수동 `./gradlew test`로 대체 확인)
- [x] 테스트·빌드 실패를 남긴 채 완료 처리하지 않았다.

## 4. 문서·사람 검수

- [x] API 경로·메서드·요청·응답이 바뀌면 README API 목록을 갱신했다. — 해당 없음 (신규 엔드포인트 없음)
- [x] 프로젝트에서 관리하는 API 명세가 있으면 갱신했다. — 해당 없음 (Swagger/OpenAPI 미사용)
- [x] DB 테이블·컬럼·인덱스가 바뀌면 migration과 관련 문서를 함께 갱신했다. — 해당 없음
- [x] 설계 결정이나 구현 현황이 바뀌면 참고 문서를 갱신했다. — `docs/rag_mvp_development_plan.md` 3단계 체크박스 갱신
- [ ] claude-kit Review Hook의 검수 결과를 확인했다. — 미확인, 사람 검수 필요
- [ ] Critical·High 위험 변경은 사람이 검수했다. — 신규 예외 체계(`RagException`) 도입, `build.gradle`에 Lombok 의존성 추가는 사람 검수 권장
- [x] 외부 부작용·권한·트랜잭션·동시성·삭제·시크릿 변경 여부를 직접 확인했다. — 외부 호출(embedding API)만 추가, 삭제/트랜잭션 없음. API Key 등 시크릿은 코드에 없음(BGE-M3 엔드포인트는 인증 불필요로 확인됨)

## 5. 완료 기록

- 변경 파일:
  - `build.gradle` (Lombok 의존성 추가)
  - `src/main/resources/application.yml` (embedding 설정 추가)
  - `.env` (신규, git 미추적)
  - `src/main/java/com/nohtaehwan/rag/exception/RagException.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/embedding/EmbeddingProperties.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/embedding/EmbeddingRequest.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/embedding/EmbeddingResponse.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/embedding/EmbeddingConfig.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/embedding/EmbeddingClient.java` (신규)
  - `src/test/java/com/nohtaehwan/rag/embedding/EmbeddingClientTest.java` (신규)
  - `docs/rag_mvp_development_plan.md` (3단계 체크박스 갱신)
- 실행한 검증 명령:
  - `./gradlew test`
  - `curl http://100.90.113.121:8003/v1/embeddings ...` (실제 서버 응답 확인)
- 실제 검증 결과:
  - `./gradlew test` → `BUILD SUCCESSFUL`, 4 tests, 0 failures
  - 실제 BGE-M3 호출 → HTTP 200, `dimension=1024`, `model=BAAI/bge-m3`
- 미완료 항목과 사유:
  - claude-kit Stop/Review Hook 결과 미확인 — 세션에서 훅 실행 로그를 직접 확인하지 못함, 수동 테스트로 대체

## 6. 리뷰 반영 (claude-kit Stop Hook 검수 후속 조치)

claude-kit Stop Hook 자동 검수(Medium 3건, Low 2건) 중 아래 3건을 반영했다.

- Medium — `EmbeddingClient.embed()`에서 `data[0].embedding`이 `null`/빈 값이면 NPE 대신 `RagException`을 던지도록 수정, 회귀 테스트(`embed_throwsRagException_whenEmbeddingFieldMissing`) 추가
- Medium — `EmbeddingProperties`에 compact constructor 검증 추가(`baseUrl`/`model` 빈 값, `dimension` 0 이하 시 `IllegalStateException`), `EmbeddingPropertiesTest` 신규 작성
- Low — `.env.example` 신규 생성, `docs/rag_mvp_development_plan.md` 3단계에 `.env` 로딩 방법(`cp .env.example .env` → `source .env` → `./gradlew bootRun`) 안내 추가

미반영(범위 밖으로 판단):
- Medium(테스트가 4차원 Mock 사용) — 의도된 테스트 설계, 실제 1024차원 검증은 이 세션에서 수동으로 이미 확인함
- Low(DB 비밀번호 기본값 정리) — 이번 작업 범위(Embedding 연동) 밖, 기존 2단계 설정이라 별도 논의 필요

**변경 파일 (추가)**
- `src/main/java/com/nohtaehwan/rag/embedding/EmbeddingClient.java` (null 체크 추가)
- `src/main/java/com/nohtaehwan/rag/embedding/EmbeddingProperties.java` (compact constructor 검증 추가)
- `src/test/java/com/nohtaehwan/rag/embedding/EmbeddingClientTest.java` (테스트 케이스 추가)
- `src/test/java/com/nohtaehwan/rag/embedding/EmbeddingPropertiesTest.java` (신규)
- `.env.example` (신규)
- `docs/rag_mvp_development_plan.md` (환경변수 로딩 안내 추가)

**검증 결과**
- `./gradlew test --rerun` → `BUILD SUCCESSFUL`, 8 tests(`EmbeddingClientTest` 4, `EmbeddingPropertiesTest` 3, `RagBackendApplicationTests` 1), 0 failures
- 사람 검수가 필요한 사항:
  - `RagException` 신설(프로젝트 최초 단일 예외 클래스) 및 명명이 적절한지
  - `build.gradle`에 Lombok 의존성 추가가 적절한지
  - 실제 DGX Spark 주소(`100.90.113.121:8003`)가 HTTP/2 협상 시 body 누락 문제를 보인 원인 — 서버 측(sglang/uvicorn 등) HTTP/2 처리 이슈로 추정되나 서버 로그는 확인하지 못함. 현재는 `SimpleClientHttpRequestFactory`로 HTTP/1.1을 강제해 우회함

## 완료 조건

```text
CLAUDE.md·관련 Skill 확인
→ 테스트 작성·수정
→ ./gradlew test 통과
→ Stop Hook 결과 확인
→ 문서 동기화
→ 위험 변경 사람 검수
→ 완료 기록 작성
```

Stop Hook 결과 확인과 사람 검수는 미완료 상태로 남겨두며, 위 "사람 검수가 필요한 사항"을 사용자에게 별도 보고한다.
