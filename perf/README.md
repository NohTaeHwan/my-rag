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

자세한 각 단계 명령은 `dataset/README.md`, `evaluation/README.md`(작성 예정), `load/README.md`(작성 예정)에 정리한다.
