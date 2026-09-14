#!/usr/bin/env python3
"""queries.jsonl이 schema.json과 의미적 규칙을 만족하는지 확인한다.

의미적 규칙(schema.json으로 표현 안 되는 것들):
  - query_id는 파일 전체에서 유일해야 한다.
  - unanswerable=true인 레코드는 relevant_sources/relevant_chunk_keys가 비어 있어야 한다.
  - category="unanswerable"이면 unanswerable도 true여야 한다(반대는 강제하지 않는다 —
    ambiguous 등 다른 category도 unanswerable일 수 있음).

사용 예:
  python3 validate_queries.py --queries queries_s.jsonl --schema schema.json
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import jsonschema


def load_jsonl(path: Path) -> list[dict]:
    records = []
    with path.open(encoding="utf-8") as f:
        for line_no, line in enumerate(f, start=1):
            line = line.strip()
            if not line:
                continue
            try:
                records.append(json.loads(line))
            except json.JSONDecodeError as e:
                raise SystemExit(f"{path}:{line_no}: JSON 파싱 실패 — {e}")
    return records


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--queries", required=True, type=Path)
    parser.add_argument("--schema", required=True, type=Path)
    args = parser.parse_args()

    schema = json.loads(args.schema.read_text(encoding="utf-8"))
    records = load_jsonl(args.queries)

    errors: list[str] = []
    seen_ids: set[str] = set()

    for i, record in enumerate(records):
        try:
            jsonschema.validate(record, schema)
        except jsonschema.ValidationError as e:
            errors.append(f"[{i}] schema 위반: {e.message}")
            continue

        qid = record["query_id"]
        if qid in seen_ids:
            errors.append(f"[{i}] query_id 중복: {qid}")
        seen_ids.add(qid)

        if record["unanswerable"] and (record["relevant_sources"] or record["relevant_chunk_keys"]):
            errors.append(f"[{i}] {qid}: unanswerable=true인데 relevant_sources/relevant_chunk_keys가 비어있지 않음")

        if record["category"] == "unanswerable" and not record["unanswerable"]:
            errors.append(f"[{i}] {qid}: category=unanswerable인데 unanswerable=false")

    if errors:
        print(f"검증 실패: {len(errors)}건")
        for e in errors:
            print(f"  - {e}")
        return 1

    print(f"검증 통과: {len(records)}개 질의, query_id 중복 없음")
    return 0


if __name__ == "__main__":
    sys.exit(main())
