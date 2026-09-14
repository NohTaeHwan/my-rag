"""검색 정확도 지표 계산 (Hit@K, Recall@K, MRR, nDCG@K).

순수 함수만 제공한다 — I/O나 HTTP 호출은 evaluate_retrieval.py에서 담당한다.
"relevant" 판정은 chunk_key(예: "source.md#3") 단위로 하며, chunk_key가 아직
없으면(resolve 전) source만으로도 비교할 수 있도록 대체 로직을 둔다.
"""
from __future__ import annotations

import math


def hit_at_k(retrieved_keys: list[str], relevant_keys: set[str], k: int) -> int:
    if not relevant_keys:
        return 0
    return 1 if set(retrieved_keys[:k]) & relevant_keys else 0


def recall_at_k(retrieved_keys: list[str], relevant_keys: set[str], k: int) -> float:
    if not relevant_keys:
        return 0.0
    hit_count = len(set(retrieved_keys[:k]) & relevant_keys)
    return hit_count / len(relevant_keys)


def reciprocal_rank(retrieved_keys: list[str], relevant_keys: set[str]) -> float:
    for rank, key in enumerate(retrieved_keys, start=1):
        if key in relevant_keys:
            return 1.0 / rank
    return 0.0


def ndcg_at_k(retrieved_keys: list[str], relevant_keys: set[str], k: int) -> float:
    if not relevant_keys:
        return 0.0
    dcg = 0.0
    for i, key in enumerate(retrieved_keys[:k], start=1):
        if key in relevant_keys:
            dcg += 1.0 / math.log2(i + 1)
    ideal_hits = min(len(relevant_keys), k)
    idcg = sum(1.0 / math.log2(i + 1) for i in range(1, ideal_hits + 1))
    return dcg / idcg if idcg > 0 else 0.0


def evaluate_single_query(retrieved_keys: list[str], relevant_keys: set[str], ks: tuple[int, ...] = (1, 3, 5, 10)) -> dict:
    result = {"mrr": reciprocal_rank(retrieved_keys, relevant_keys)}
    for k in ks:
        result[f"hit@{k}"] = hit_at_k(retrieved_keys, relevant_keys, k)
        result[f"recall@{k}"] = recall_at_k(retrieved_keys, relevant_keys, k)
        result[f"ndcg@{k}"] = ndcg_at_k(retrieved_keys, relevant_keys, k)
    return result


def aggregate(per_query_results: list[dict]) -> dict:
    """여러 질의 결과를 평균낸다. 숫자(int/float, bool 제외) 필드만 평균 대상이다 —
    query_id/category처럼 메타데이터로 섞여 들어온 문자열 필드는 자동으로 건너뛴다.
    빈 리스트면 빈 dict를 반환한다.
    """
    if not per_query_results:
        return {}
    numeric_keys = [
        k for k, v in per_query_results[0].items()
        if isinstance(v, (int, float)) and not isinstance(v, bool)
    ]
    return {k: sum(r[k] for r in per_query_results) / len(per_query_results) for k in numeric_keys}


if __name__ == "__main__":
    # 최소 smoke test — 별도 테스트 프레임워크 없이 assert로만 확인한다.
    retrieved = ["a.md#1", "b.md#2", "c.md#1", "d.md#1", "e.md#1"]
    relevant = {"c.md#1"}

    assert hit_at_k(retrieved, relevant, 5) == 1
    assert hit_at_k(retrieved, relevant, 2) == 0
    assert recall_at_k(retrieved, relevant, 5) == 1.0
    assert abs(reciprocal_rank(retrieved, relevant) - (1 / 3)) < 1e-9
    assert ndcg_at_k(retrieved, set(), 5) == 0.0
    assert ndcg_at_k(retrieved, relevant, 5) > 0.0
    assert hit_at_k(retrieved, set(), 5) == 0
    print("metrics.py self-check OK")
