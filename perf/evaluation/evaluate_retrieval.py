#!/usr/bin/env python3
"""GET /api/search 결과로 gold 질의(queries.jsonl) 기준 검색 정확도를 계산한다.

relevant_chunk_keys가 채워져 있으면 chunk 단위로, 비어 있으면(resolve_chunk_keys.py를
아직 안 돌린 경우) source 단위로 대체 비교한다 — 파이프라인을 처음 검증할 때는 source
단위만으로도 "검색이 맞는 문서를 찾는지" 정도는 바로 확인할 수 있다.

사용 예:
  python3 evaluate_retrieval.py --base-url http://<spark-tailscale-ip>:8090 \
      --queries ../dataset/queries_s.jsonl --out ../results/retrieval_s.json
"""
from __future__ import annotations

import argparse
import json
import statistics
import time
import urllib.parse
import urllib.request
from pathlib import Path

from metrics import aggregate, evaluate_single_query


def search(base_url: str, query: str, timeout: float) -> tuple[list[dict], float]:
    url = f"{base_url}/api/search?" + urllib.parse.urlencode({"query": query})
    start = time.monotonic()
    with urllib.request.urlopen(url, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    elapsed_ms = (time.monotonic() - start) * 1000
    return body.get("results", []), elapsed_ms


def build_retrieved_keys(results: list[dict], use_chunk_level: bool) -> list[str]:
    if use_chunk_level:
        return [f"{r['source']}#{r['chunkIndex']}" for r in results]
    return [r["source"] for r in results]


def load_queries(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def evaluate(base_url: str, queries: list[dict], timeout: float) -> dict:
    per_query = []
    for q in queries:
        if q.get("unanswerable"):
            # unanswerable 질의는 Recall/MRR 대상이 아니라 "결과가 얼마나 무관한지"만 별도 기록한다.
            results, latency_ms = search(base_url, q["question"], timeout)
            per_query.append({
                "query_id": q["query_id"],
                "category": q["category"],
                "unanswerable": True,
                "result_count": len(results),
                "latency_ms": latency_ms,
            })
            continue

        use_chunk_level = bool(q.get("relevant_chunk_keys"))
        relevant_keys = set(q["relevant_chunk_keys"]) if use_chunk_level else set(q["relevant_sources"])

        results, latency_ms = search(base_url, q["question"], timeout)
        retrieved_keys = build_retrieved_keys(results, use_chunk_level)

        metrics_result = evaluate_single_query(retrieved_keys, relevant_keys)
        per_query.append({
            "query_id": q["query_id"],
            "category": q["category"],
            "difficulty": q.get("difficulty"),
            "latency_ms": latency_ms,
            "match_level": "chunk" if use_chunk_level else "source",
            **metrics_result,
        })

    answerable = [r for r in per_query if not r.get("unanswerable")]
    by_category: dict[str, list[dict]] = {}
    for r in answerable:
        by_category.setdefault(r["category"], []).append(r)

    latencies = [r["latency_ms"] for r in per_query]
    summary = {
        "query_count": len(queries),
        "answerable_count": len(answerable),
        "overall": aggregate(answerable),
        "by_category": {cat: aggregate(rs) for cat, rs in by_category.items()},
        "latency_ms": {
            "p50": statistics.median(latencies) if latencies else None,
            "p95": (statistics.quantiles(latencies, n=20)[18] if len(latencies) >= 20
                    else max(latencies) if latencies else None),
            "min": min(latencies) if latencies else None,
            "max": max(latencies) if latencies else None,
        },
    }
    return {"summary": summary, "per_query": per_query}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="예: http://127.0.0.1:8090")
    parser.add_argument("--queries", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--timeout", type=float, default=10.0)
    args = parser.parse_args()

    queries = load_queries(args.queries)
    result = evaluate(args.base_url.rstrip("/"), queries, args.timeout)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    s = result["summary"]
    print(f"질의 {s['query_count']}개 중 답변 가능 {s['answerable_count']}개 평가")
    print(f"overall: {s['overall']}")
    print(f"latency p50/p95: {s['latency_ms']['p50']:.1f}ms / {s['latency_ms']['p95']:.1f}ms" if s['latency_ms']['p50'] is not None else "latency: N/A")
    print(f"결과 저장 → {args.out}")


if __name__ == "__main__":
    main()
