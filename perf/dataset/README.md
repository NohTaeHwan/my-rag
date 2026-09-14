# 평가 데이터셋

## 생성

```bash
python3 generate_dataset.py --size s --out-dir generated/s --queries-out queries_s.jsonl --query-sample-size 20
```

- `--size`: `s`(100) / `m`(1,000) / `l`(10,000) / `xl`(50,000)
- 문서는 `{out-dir}/{domain}-{index}.md` 형태로 생성되며, source는 `perf-test/{size}/{domain}-{index}.md`로 고정된다.
- 같은 `--seed`(기본 42)면 항상 같은 결과가 나온다 — 데이터셋 버전을 이 시드값으로 고정해서 기록한다.
- 도메인 6개(주문취소/환불/배송/회원/결제/장애대응) 각각에 숫자·기간 등 하나의 "정답 값"을 파라미터로 넣어, 같은 도메인 안에서도 값만 다른 hard negative 문서를 자동으로 만든다.

## 검증

```bash
python3 validate_queries.py --queries queries_s.jsonl --schema schema.json
```

## chunk key 채우기 (색인 후, Spark에서 실행)

`queries.jsonl`은 생성 시점에는 `relevant_chunk_keys`가 비어 있다(실제 색인 결과를 봐야 알 수 있으므로). 색인이 끝난 뒤 Spark에서:

```bash
python3 resolve_chunk_keys.py --in queries_s.jsonl --out queries_s.resolved.jsonl
```

내부적으로 `docker exec rag-postgres psql`로 직접 조회한다 — 별도 Python 패키지 설치가 필요 없다.

## 질의 레코드 스키마

`schema.json` 참고. 요약:

| 필드 | 설명 |
|---|---|
| `query_id` | 유일 ID |
| `question` | 질문 원문 |
| `category` | `direct_lookup`/`multi_chunk`/`paraphrase`/`keyword_identifier`/`hard_negative`/`unanswerable`/`ambiguous` |
| `relevant_sources` | 정답 문서의 source 목록 |
| `relevant_chunk_keys` | `"{source}#{chunk_index}"` 형식, 색인 후 `resolve_chunk_keys.py`로 채움 |
| `expected_facts` | 답변에 포함돼야 하는 사실(문자열) |
| `unanswerable` | 데이터셋에 근거가 없는 질문이면 true |

## 주의

- S 데이터셋은 파이프라인 검증용이라 질의를 템플릿으로 자동 생성했다 — "생성 모델의 답변을 정답으로 자동 채택하지 않는다"는 계획서 원칙과 다른 것은, 여기서는 LLM이 아니라 **생성 스크립트 자신이 파라미터로 박아 넣은 값**이 곧 정답이기 때문이다(자기 자신의 판단을 정답으로 쓰는 게 아님).
- M/L 이상으로 갈 때는 `labeling-guide.md`에 따라 사람이 직접 작성/검토한 gold 질의를 섞어서 신뢰도를 높인다.
