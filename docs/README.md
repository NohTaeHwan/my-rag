# my-rag 문서 이정표

프로젝트의 문서를 **찾으려는 내용별로** 안내한다. 경로는 저장소 루트 기준이며, 링크는 이 문서에서 바로 열 수 있다. 이 목록은 코드·설정·생성된 평가용 Markdown 데이터가 아닌 **사람이 읽는 문서와 작업 계획**을 대상으로 한다.

> 보관 상태: `docs/rag_performance_results.md`와 `docs/private/`는 `.gitignore` 대상이다. `docs/personal_rag_chatbot_design.md`는 현재 미추적, `.hermes/` 전체는 `.gitignore` 대상이다. 로컬 전용 자료는 clone에서 보이지 않을 수 있으므로 공유 전에 Git 상태를 확인한다.

## 가장 먼저 찾을 문서

| 찾는 내용 | 문서 | 한 줄 요약 |
|---|---|---|
| 프로젝트 소개·실행·API | [README.md](../README.md) | 기술 스택, 실행 방법, API와 프로젝트 구조를 설명한다. |
| 현재 MVP 단계·다음 작업 | [rag_mvp_development_plan.md](rag_mvp_development_plan.md) | RAG MVP의 구현 단계와 챗봇 구현 후 재평가 항목을 기록한다. |
| MVP 성능·품질 평가 | [rag_performance_results.md](rag_performance_results.md) | 합성 데이터 기반 평가 수치·병목·한계와 후속 재평가 계획을 정리한다(로컬 전용). |
| 문서 기반 RAG 챗봇 설계 | [personal_rag_chatbot_design.md](personal_rag_chatbot_design.md) | 범용(도메인 무관) RAG 챗봇의 사용자·Knowledge Base·업로드 데이터 모델과 API 명세를 정리한다. Memory·대화 요약은 후순위 선택 기능으로 분리했다(미추적). |
| 문서 기반 RAG 챗봇 구현 순서 | [구현 계획](../.hermes/plans/2026-09-21_151055-personal-rag-chatbot-implementation.md) | Phase 1~4(핵심: 사용자별 KB 범위 확장→업로드→답변→동시성 제어) 이후 Memory/요약(선택)·웹 UI·통합 검증까지 단계별로 정리한다(로컬 전용). |

## 개념·설계·개발 규칙 (`docs/`, 루트)

| 문서 | 한 줄 요약 |
|---|---|
| [rag_basic_concepts_and_use_cases.md](rag_basic_concepts_and_use_cases.md) | RAG 기본 구조와 활용 사례를 학습하는 문서다. |
| [rag_backend_developer.md](rag_backend_developer.md) | 백엔드 개발자 관점에서 RAG 구성 요소와 확장 지점을 설명한다. |
| [CLAUDE.md](../CLAUDE.md) | 프로젝트 구조, 개발 규칙, 작업 전 읽을 문서 이정표를 정의한다. |
| [claude_kit_development_checklist.md](claude_kit_development_checklist.md) | 기능별 구현·테스트·문서 갱신 시 작성할 공통 체크리스트다. |
| [claude_kit_verification.md](claude_kit_verification.md) | claude-kit 설정과 Hook 동작의 실제 검증 방법·결과를 기록한다. |

## 실행·배포·데이터베이스

현재 앱 설정은 Flyway 자동 적용을 비활성화한다(`application.yml`). 로컬 앱은 DDL 권한이 없는 `rag_app`으로 접속하며, 관리자 계정은 로컬 DB 변경을 사용자가 승인했을 때만 사용한다. Spark의 계정·스키마는 변경하지 않았으므로 배포 전 별도 준비가 필요하다.

| 문서 | 한 줄 요약 |
|---|---|
| [local_embedding_model_setup_dgx_spark.md](local_embedding_model_setup_dgx_spark.md) | DGX Spark의 BGE-M3 Embedding 모델 설치·운영 구성을 설명한다. |
| [dgx_spark_mvp_deployment.md](dgx_spark_mvp_deployment.md) | DGX Spark에 API·DB·Embedding·LLM을 배포하고 확인하는 절차를 기록한다. |
| [database_migration_history.md](database_migration_history.md) | V1–V4의 변경 요약, 로컬 `rag_db`의 적용 상태, 격리 검증 범위와 DB 계정 분리를 기록한다. |
| [V1__init_rag_schema.sql](../src/main/resources/db/migration/V1__init_rag_schema.sql) | Flyway가 적용하는 pgvector 및 최초 문서·Chunk 스키마다. |
| [V2__add_unique_constraint_to_document_source.sql](../src/main/resources/db/migration/V2__add_unique_constraint_to_document_source.sql) | `tb_document.source`의 유일성 제약을 추가하는 Flyway migration이다. |
| [V3__add_user_knowledge_base_scope.sql](../src/main/resources/db/migration/V3__add_user_knowledge_base_scope.sql) | `tb_user`·`tb_knowledge_base`를 추가하고 `tb_document`를 사용자·KB 범위로 확장한다. `source` 유일성 제약을 전역에서 `(user_id, knowledge_base_id, source)` 범위로 교체한다(검색 SQL의 범위 제한은 다음 작업 단위, 미완료). |
| [V4__enforce_document_knowledge_base_owner.sql](../src/main/resources/db/migration/V4__enforce_document_knowledge_base_owner.sql) | `tb_document`의 사용자와 Knowledge Base 소유자가 일치하도록 복합 FK를 추가한다. |
| [001_init_rag_schema.sql](../db/migrations/001_init_rag_schema.sql) | 초기 스키마를 확인하기 위한 별도 SQL 문서이며, 앱의 Flyway 적용 경로는 위 `src/main/resources/db/migration/`이다. |

## 성능 평가 자료 (`perf/`)

| 문서 | 한 줄 요약 |
|---|---|
| [perf/README.md](../perf/README.md) | 데이터셋 생성부터 색인·검색·답변 측정까지 평가 자산의 입구다. |
| [perf/dataset/README.md](../perf/dataset/README.md) | 합성 문서와 정답 질의 데이터의 생성·검증 방법을 설명한다. |
| [perf/dataset/labeling-guide.md](../perf/dataset/labeling-guide.md) | 평가 질의의 정답 출처와 기대 사실을 사람이 검토하는 기준이다. |
| [성능·정확도 평가 계획](../.hermes/plans/2026-09-10_180338-rag-mvp-performance-evaluation.md) | 색인·검색·답변 성능과 품질을 분리 측정하기 위한 계획이다(로컬 전용). |

## 작업별 기록 (`docs/checklists/`)

공통 서식은 [claude_kit_development_checklist.md](claude_kit_development_checklist.md), 아래는 각 개발 작업의 실행·검증 기록이다.

| 문서 | 한 줄 요약 |
|---|---|
| [2026-09-02_embedding-client.md](checklists/2026-09-02_embedding-client.md) | BGE-M3 Embedding 연동 작업의 체크 결과다. |
| [2026-09-03_markdown-chunking.md](checklists/2026-09-03_markdown-chunking.md) | Markdown 파싱·Chunking 작업의 체크 결과다. |
| [2026-09-04_document-indexing.md](checklists/2026-09-04_document-indexing.md) | 문서 색인과 DB 저장 작업의 체크 결과다. |
| [2026-09-04_mybatis-migration.md](checklists/2026-09-04_mybatis-migration.md) | DB 접근 계층의 MyBatis 전환 작업 기록이다. |
| [2026-09-08_similarity-search.md](checklists/2026-09-08_similarity-search.md) | pgvector 유사도 검색 작업 기록이다. |
| [2026-09-08_swagger-setup.md](checklists/2026-09-08_swagger-setup.md) | Swagger/OpenAPI 구성 작업 기록이다. |
| [2026-09-08_answer-generation.md](checklists/2026-09-08_answer-generation.md) | LLM 답변 생성 API 작업 기록이다. |
| [2026-10-23_user-knowledge-base-scope.md](checklists/2026-10-23_user-knowledge-base-scope.md) | 사용자·Knowledge Base DB 범위 전환(V3), 소유자 복합 FK(V4), 통합 테스트 cleanup 격리 보강과 검증 상태를 기록한다. |
| [2026-10-23_flyway-app-role-guard.md](checklists/2026-10-23_flyway-app-role-guard.md) | Flyway 자동 적용 차단·로컬 앱 DB 제한 계정 적용과 비파괴 검증 기록이다. |

## 기획·구현 이력 (`.hermes/plans/`, 로컬 전용)

이 디렉터리는 Git에서 무시된다. 아래 문서들은 **구현 전 기획·전달용 기록**이며 현재 구현 상태는 위 `docs/`의 MVP 계획과 실제 코드를 기준으로 확인한다.

| 문서 | 한 줄 요약 |
|---|---|
| [4단계 Markdown Chunking](../.hermes/plans/2026-09-03_102020-markdown-chunking-stage4.md) | 문서 수집·파싱·분할 정책의 구현 계획이다. |
| [5-1단계 MyBatis 전환](../.hermes/plans/2026-09-04_154914-mybatis-migration-stage-5-1.md) | JdbcTemplate에서 MyBatis로 전환하는 계획이다. |
| [6단계 유사도 검색](../.hermes/plans/2026-09-07_213508-stage-6-similarity-search.md) | 질문 벡터로 관련 Chunk를 찾는 기능 계획이다. |
| [6-1단계 Swagger](../.hermes/plans/2026-09-08_114726-stage-6-1-swagger.md) | API 명세·Swagger UI 구성 계획이다. |
| [7단계 LLM 답변](../.hermes/plans/2026-09-08_133610-stage-7-llm-answer-generation.md) | 검색 근거로 답변·출처를 만드는 기능 계획이다. |
| [DGX Spark MVP 배포](../.hermes/plans/2026-09-08_161535-dgx-spark-mvp-deployment.md) | Spark 단일 환경에 RAG 구성 요소를 배치하는 계획이다. |
| [개인 지식 챗봇 기획](../.hermes/plans/2026-09-14_220046-personal-knowledge-rag-chatbot.md) | 개인 문서 기반 챗봇의 제품 방향과 사용 시나리오를 정리한 초안이다. |
| [개인용 챗봇 확장 구현](../.hermes/plans/2026-09-21_151055-personal-rag-chatbot-implementation.md) | 사용자별 Knowledge Base·업로드·Memory·UI 확장 단계 계획이다. |
| [개인용 채팅 UI 프로토타입](../.hermes/prototypes/personal-rag-chat.html) | 실제 API 연결 전의 챗봇 화면 시안이다. |

새 문서를 추가·이동하거나 Git 추적 상태가 바뀌면 이 목록과 로컬 전용 표시를 갱신한다.
