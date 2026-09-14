#!/usr/bin/env python3
"""POST /api/answers 결과로 gold 질의 기준 답변 정확도(1차 규칙 기반)를 계산한다.

여기서 나오는 점수는 계획서(Task 4)의 1차(규칙 기반) 채점이다 — Correctness/Faithfulness를
사람이 직접 보지 않고 근사치로만 판단하므로, 최종 결론은 answer-rubric.md 기준의 사람 검토(2차)와
함께 봐야 한다. 이 스크립트 혼자 "정확도 X%"를 포트폴리오 수치로 쓰지 않는다.

현재 AnswerService는 검색 결과에 유사도 임계값을 두지 않는다(top-K는 관련성과 무관하게 항상
채워진다) — 그래서 abstention(근거 없을 때 거절)은 고정 문자열 매칭이 아니라 답변 텍스트의
거절 표현 키워드로 근사 판정한다. 나중에 임계값 기능을 추가할 때를 대비해 각 질의의 최소
distance도 함께 기록한다.

사용 예:
  python3 evaluate_answers.py --base-url http://127.0.0.1:8090 \
      --queries ../dataset/queries_s.resolved.jsonl --out ../results/answers_s.json
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import time
import urllib.error
import urllib.request
from pathlib import Path

REFUSAL_PATTERNS = [
    "찾지 못했", "찾을 수 없", "근거가 없", "근거를 찾을 수 없", "확인할 수 없",
    "알 수 없습니다", "포함되어 있지 않", "답변할 수 없", "관련된 정보가 없",
    "명시되어 있지 않", "언급되어 있지 않",
]


def call_answer(base_url: str, question: str, timeout: float) -> tuple[dict | None, float, int]:
    """LLM 호출 1건. 150개를 통째로 돌리는 배치라 개별 요청의 timeout/네트워크 오류가
    전체 실행을 중단시키면 안 된다 — 실패한 질의는 status=0으로 표시하고 계속 진행한다."""
    url = f"{base_url}/api/answers"
    payload = json.dumps({"question": question}).encode("utf-8")
    req = urllib.request.Request(url, data=payload, headers={"Content-Type": "application/json"}, method="POST")
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            status = resp.status
    except urllib.error.HTTPError as e:
        return None, (time.monotonic() - start) * 1000, e.code
    except OSError:
        return None, (time.monotonic() - start) * 1000, 0
    elapsed_ms = (time.monotonic() - start) * 1000
    return body, elapsed_ms, status


def normalize(text: str) -> str:
    return re.sub(r"[`'\"]", "", text).lower()


def fact_coverage(answer: str, expected_facts: list[str]) -> float:
    if not expected_facts:
        return 1.0
    norm_answer = normalize(answer)
    hits = sum(1 for fact in expected_facts if normalize(fact) in norm_answer)
    return hits / len(expected_facts)


def looks_like_refusal(answer: str) -> bool:
    return any(p in answer for p in REFUSAL_PATTERNS)


def load_queries(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def evaluate(base_url: str, queries: list[dict], timeout: float) -> dict:
    per_query = []
    for i, q in enumerate(queries, start=1):
        if i % 10 == 0 or i == len(queries):
            print(f"진행: {i}/{len(queries)}", flush=True)
        body, latency_ms, status = call_answer(base_url, q["question"], timeout)
        success = body is not None and status == 200
        answer = body.get("answer", "") if body else ""
        sources = body.get("sources", []) if body else []
        distances = [s["distance"] for s in sources if s.get("distance") is not None]

        row = {
            "query_id": q["query_id"],
            "category": q["category"],
            "unanswerable": bool(q.get("unanswerable")),
            "http_status": status,
            "success": success,
            "answer": answer,
            "source_count": len(sources),
            "min_distance": min(distances) if distances else None,
            "latency_ms": latency_ms,
        }

        if q.get("unanswerable"):
            row["abstained"] = looks_like_refusal(answer)
        else:
            row["fact_coverage"] = fact_coverage(answer, q.get("expected_facts", []))
            relevant_sources = set(q.get("relevant_sources", []))
            row["source_hit"] = bool(relevant_sources & {s["source"] for s in sources})

        per_query.append(row)

    answerable = [r for r in per_query if not r["unanswerable"]]
    unanswerable = [r for r in per_query if r["unanswerable"]]
    latencies = [r["latency_ms"] for r in per_query]

    def avg(rows: list[dict], key: str) -> float | None:
        vals = [r[key] for r in rows if key in r]
        return sum(vals) / len(vals) if vals else None

    summary = {
        "query_count": len(queries),
        "answerable_count": len(answerable),
        "unanswerable_count": len(unanswerable),
        "answer_success_rate": avg(per_query, "success"),
        "fact_coverage": avg(answerable, "fact_coverage"),
        "source_precision": avg(answerable, "source_hit"),
        "abstention_precision": avg(unanswerable, "abstained"),
        "latency_ms": {
            "p50": statistics.median(latencies) if latencies else None,
            "p95": (statistics.quantiles(latencies, n=20)[18] if len(latencies) >= 20
                    else max(latencies) if latencies else None),
        },
    }
    return {"summary": summary, "per_query": per_query}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="예: http://127.0.0.1:8090")
    parser.add_argument("--queries", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--timeout", type=float, default=30.0)
    args = parser.parse_args()

    queries = load_queries(args.queries)
    result = evaluate(args.base_url.rstrip("/"), queries, args.timeout)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    s = result["summary"]
    print(f"질의 {s['query_count']}개 (답변가능 {s['answerable_count']} / 무근거 {s['unanswerable_count']})")
    print(f"answer_success_rate={s['answer_success_rate']:.3f}")
    print(f"fact_coverage={s['fact_coverage']:.3f}  source_precision={s['source_precision']:.3f}"
          if s['fact_coverage'] is not None else "fact_coverage=N/A")
    print(f"abstention_precision={s['abstention_precision']:.3f}" if s['abstention_precision'] is not None else "abstention_precision=N/A")
    if s["latency_ms"]["p50"] is not None:
        print(f"latency p50/p95: {s['latency_ms']['p50']:.1f}ms / {s['latency_ms']['p95']:.1f}ms")
    print(f"결과 저장 → {args.out}")


if __name__ == "__main__":
    main()
