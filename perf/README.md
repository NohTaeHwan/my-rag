# RAG MVP 성능·정확도 평가

계획 문서: `.hermes/plans/2026-09-10_180338-rag-mvp-performance-evaluation.md`
결과 보고서(작성 예정): `docs/rag_performance_results.md`

## 디렉터리 구조

```text
perf/
├── dataset/       합성 문서·gold 질의 생성/검증/resolve
├── evaluation/    검색·답변 정확도 계산
├── load/          k6 부하 시나리오 (색인/검색/답변)
├── report/        결과 집계·차트·보고서 생성
└── results/       실행 결과(raw JSON, 차트) 보관 — 커밋 대상 아님(README만 예외)
```

## 실행 대상 환경

지금은 실사용 전이라 별도로 격리하지 않고, **DGX Spark에 이미 배포된 my-rag-app(8090)/rag-postgres(5433)**를 그대로 사용한다. 합성 테스트 문서는 전부 `perf-test/`로 시작하는 source 경로를 쓰므로, 테스트 종료 후 다음으로 깨끗하게 정리할 수 있다.

```bash
docker exec rag-postgres psql -U rag -d rag_db -c "DELETE FROM tb_document WHERE source LIKE 'perf-test/%';"
```

(`tb_document_chunk`는 `ON DELETE CASCADE`라 문서 삭제 시 같이 지워진다.)

## 실행 순서 (요약)

1. `dataset/generate_dataset.py`로 문서+질의 생성 (로컬, 맥북)
2. 생성된 `.md`를 Spark의 `data/markdown` 마운트 경로로 scp
3. `POST /api/documents/index` 호출 (색인)
4. `dataset/resolve_chunk_keys.py`를 **Spark에서** 실행해 gold 질의의 실제 chunk_index 채우기
5. `evaluation/evaluate_retrieval.py`로 검색 정확도 계산
6. k6로 부하 테스트 (`load/`)
7. `report/generate_report.py`로 보고서·차트 생성

### knowledgeBaseId 필수 입력 (2026-10-28~)

`/api/search`·`/api/answers`는 `knowledgeBaseId`가 필수라(없으면 400) 검색·답변 호출자는 모두 KB id를 명시로 받는다. 기본값(KB 1)을 암묵적으로 넣지 않으며, 누락·0·음수·숫자 아님은 요청 전에 실패한다.

| 호출자 | 입력 방법 |
|---|---|
| `evaluation/evaluate_retrieval.py`, `evaluation/evaluate_answers.py` | `--knowledge-base-id <양의 정수>` (필수) |
| `load/run_search_load.sh`, `load/run_answer_load.sh` | 환경변수 `KNOWLEDGE_BASE_ID=<양의 정수>` (필수, k6 컨테이너에 그대로 전달) |
| `load/search-load.js`, `load/answer-load.js` (k6 직접 실행) | 환경변수 `KNOWLEDGE_BASE_ID` (필수) |

**선택한 KB와 데이터셋이 일치해야 한다**: 3단계에서 `perf-test/` 문서를 색인한 KB(현재 색인 API는 개발용 사용자 1의 기본 KB에 색인)의 id를 넘겨야 한다. gold 문서(`relevant_sources`/`relevant_chunk_keys`)가 그 KB에 `INDEXED` 상태로 있지 않으면 Recall/MRR·source_hit·fact_coverage가 0에 가깝게 나와 정확도 해석이 무효다. 또한 타인 소유·존재하지 않는 KB는 404가 된다.

오프라인 입력 검증 테스트(네트워크·Spark·DB 미사용, k6 테스트는 loopback stub만 사용): `python3 perf/test_knowledge_base_id.py`

자세한 각 단계 명령은 `dataset/README.md`, `evaluation/README.md`(작성 예정), `load/README.md`(작성 예정)에 정리한다.
