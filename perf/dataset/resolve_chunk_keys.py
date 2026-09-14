#!/usr/bin/env python3
"""색인 완료 후, queries.jsonl의 relevant_chunk_keys를 실제 DB 조회로 채운다.

Spark에서 실행한다(docker exec로 rag-postgres 컨테이너에 직접 psql 질의하므로, DB
컨테이너가 로컬에서 보이는 환경이 필요하다 — 별도 Python DB 드라이버 설치 불필요).

expected_facts에 있는 값(예: "3", "5~7")이 실제로 어느 chunk_index에 들어있는지
LIKE 검색으로 찾아서 "{source}#{chunk_index}" 형식의 key를 채운다.

사용 예 (Spark에서):
  python3 resolve_chunk_keys.py --in queries_s.jsonl --out queries_s.resolved.jsonl
"""
from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


def query_chunk_indexes(container: str, db: str, user: str, source: str, fact: str) -> list[int]:
    escaped_source = source.replace("'", "''")
    escaped_fact = fact.replace("'", "''").replace("%", "\\%")
    sql = (
        "SELECT dc.chunk_index FROM tb_document_chunk dc "
        "JOIN tb_document d ON d.id = dc.document_id "
        f"WHERE d.source = '{escaped_source}' AND dc.content LIKE '%{escaped_fact}%' "
        "ORDER BY dc.chunk_index;"
    )
    result = subprocess.run(
        ["docker", "exec", container, "psql", "-U", user, "-d", db, "-t", "-A", "-c", sql],
        capture_output=True, text=True, check=True,
    )
    lines = [line.strip() for line in result.stdout.splitlines() if line.strip()]
    return [int(x) for x in lines]


def resolve(queries: list[dict], container: str, db: str, user: str) -> tuple[list[dict], int]:
    resolved = []
    unresolved_count = 0
    for q in queries:
        if q.get("unanswerable") or not q.get("relevant_sources"):
            resolved.append(q)
            continue

        chunk_keys: list[str] = []
        for source in q["relevant_sources"]:
            for fact in q.get("expected_facts") or []:
                if not fact:
                    continue
                indexes = query_chunk_indexes(container, db, user, source, fact)
                chunk_keys.extend(f"{source}#{i}" for i in indexes)

        chunk_keys = sorted(set(chunk_keys))
        if not chunk_keys:
            unresolved_count += 1
        resolved.append({**q, "relevant_chunk_keys": chunk_keys})
    return resolved, unresolved_count


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--in", dest="in_path", required=True, type=Path)
    parser.add_argument("--out", dest="out_path", required=True, type=Path)
    parser.add_argument("--container", default="rag-postgres")
    parser.add_argument("--db", default="rag_db")
    parser.add_argument("--user", default="rag")
    args = parser.parse_args()

    with args.in_path.open(encoding="utf-8") as f:
        queries = [json.loads(line) for line in f if line.strip()]

    resolved, unresolved_count = resolve(queries, args.container, args.db, args.user)

    with args.out_path.open("w", encoding="utf-8") as f:
        for q in resolved:
            f.write(json.dumps(q, ensure_ascii=False) + "\n")

    print(f"{len(resolved)}개 질의 처리, chunk key 미발견 {unresolved_count}개")
    if unresolved_count:
        print("미발견 질의는 색인이 아직 안 됐거나 expected_facts 표현이 실제 문서 문구와 다를 수 있습니다.")
    print(f"결과 저장 → {args.out_path}")


if __name__ == "__main__":
    main()
