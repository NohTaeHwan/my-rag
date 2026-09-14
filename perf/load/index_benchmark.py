#!/usr/bin/env python3
"""POST /api/documents/index 색인 성능(총 소요시간·처리량)을 사이즈별로 측정한다.

색인 API는 DOCUMENT_SOURCE_DIRECTORY 전체를 한 번에 처리하므로, 측정 대상 사이즈만
깨끗하게 재도록 perf-test/ 아래 다른 사이즈 디렉터리는 실행 중 임시로 옮겨뒀다가
끝나면 되돌린다(파일을 지우지 않는다 — shutil.move만 사용).

사용 예 (Spark에서 직접 실행 — my-rag-app이 Spark 자체 Embedding을 호출해야 왕복 지연이
섞이지 않는다):
  python3 index_benchmark.py --base-url http://127.0.0.1:8090 \
      --markdown-root ~/services/my-rag/data/markdown --size m \
      --out ../results/index/index_m.json
"""
from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path


class BenchmarkError(Exception):
    pass


def run_psql(container: str, db: str, user: str, sql: str) -> str:
    result = subprocess.run(
        ["docker", "exec", container, "psql", "-U", user, "-d", db, "-t", "-A", "-c", sql],
        capture_output=True, text=True, timeout=30,
    )
    if result.returncode != 0:
        raise BenchmarkError(f"psql 실패: {result.stderr.strip()}")
    return result.stdout.strip()


def delete_existing(container: str, db: str, user: str) -> None:
    run_psql(container, db, user, "DELETE FROM tb_document WHERE source LIKE 'perf-test/%';")


def count_rows(container: str, db: str, user: str, scope: str = "perf-test") -> tuple[int, int]:
    """scope='perf-test'면 perf-test/% 문서만, 'all'이면 DB 전체를 센다.

    색인 API는 DOCUMENT_SOURCE_DIRECTORY 전체(perf-test 외 실제 운영 문서 포함)를 한 번에
    처리하므로, API가 보고하는 documentCount/chunkCount와 비교할 때는 'all' 스코프를 써야 한다."""
    where = "WHERE source LIKE 'perf-test/%'" if scope == "perf-test" else ""
    doc_count = int(run_psql(container, db, user, f"SELECT count(*) FROM tb_document {where};"))
    chunk_join_where = "WHERE d.source LIKE 'perf-test/%'" if scope == "perf-test" else ""
    chunk_count = int(run_psql(container, db, user,
        "SELECT count(*) FROM tb_document_chunk dc JOIN tb_document d ON d.id = dc.document_id "
        f"{chunk_join_where};"))
    return doc_count, chunk_count


def hide_sibling_sizes(markdown_root: Path, size: str) -> list[tuple[Path, Path]]:
    """측정 대상 size만 남기고 나머지 perf-test/{other} 디렉터리를 임시 폴더로 옮긴다.
    반환값 [(임시 경로, 원래 경로), ...]는 restore_sibling_sizes로 되돌릴 때 쓴다."""
    perf_test_dir = markdown_root / "perf-test"
    if not perf_test_dir.exists():
        raise BenchmarkError(f"{perf_test_dir} 없음 — 데이터셋을 먼저 업로드하세요.")
    if not (perf_test_dir / size).exists():
        raise BenchmarkError(f"perf-test/{size} 디렉터리가 없습니다.")

    hide_root = markdown_root / ".index-bench-hidden"
    hide_root.mkdir(exist_ok=True)
    moved: list[tuple[Path, Path]] = []
    for p in perf_test_dir.iterdir():
        if p.is_dir() and p.name != size:
            dest = hide_root / p.name
            shutil.move(str(p), str(dest))
            moved.append((dest, p))
    return moved


def restore_sibling_sizes(moved: list[tuple[Path, Path]]) -> None:
    for dest, original in moved:
        shutil.move(str(dest), str(original))


def call_index(base_url: str, timeout: float) -> tuple[dict | None, float, int]:
    req = urllib.request.Request(
        f"{base_url}/api/documents/index", data=b"{}",
        headers={"Content-Type": "application/json"}, method="POST",
    )
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            status = resp.status
    except urllib.error.HTTPError as e:
        return None, (time.monotonic() - start) * 1000, e.code
    except OSError:
        return None, (time.monotonic() - start) * 1000, 0
    return body, (time.monotonic() - start) * 1000, status


def run_benchmark(base_url: str, markdown_root: Path, size: str,
                   container: str, db: str, user: str, timeout: float) -> dict:
    pre_doc, _ = count_rows(container, db, user)
    if pre_doc != 0:
        raise BenchmarkError(f"색인 전 DB에 perf-test 문서가 {pre_doc}개 남아있습니다 — 먼저 삭제하세요.")

    moved = hide_sibling_sizes(markdown_root, size)
    try:
        body, elapsed_ms, status = call_index(base_url, timeout)
    finally:
        restore_sibling_sizes(moved)

    db_doc, db_chunk = count_rows(container, db, user, scope="perf-test")
    total_doc, total_chunk = count_rows(container, db, user, scope="all")
    success = body is not None and status == 200

    result = {
        "size": size,
        "success": success,
        "http_status": status,
        "elapsed_ms": elapsed_ms,
        "reported": body or {},
        "db_document_count": db_doc,
        "db_chunk_count": db_chunk,
        "db_total_document_count": total_doc,
        "db_total_chunk_count": total_chunk,
        # API는 DOCUMENT_SOURCE_DIRECTORY 전체(perf-test 외 실제 운영 문서 포함)를 처리하므로
        # 보고값은 perf-test 스코프가 아니라 전체 카운트와 비교해야 한다.
        "counts_match": success and body.get("documentCount") == total_doc and body.get("chunkCount") == total_chunk,
    }
    if success and db_doc and elapsed_ms > 0:
        result["documents_per_minute"] = db_doc / (elapsed_ms / 1000 / 60)
        result["chunks_per_second"] = db_chunk / (elapsed_ms / 1000)
        result["avg_ms_per_document"] = elapsed_ms / db_doc
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="예: http://127.0.0.1:8090")
    parser.add_argument("--markdown-root", required=True, type=Path)
    parser.add_argument("--size", required=True, choices=["s", "m", "l", "xl"])
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--container", default="rag-postgres")
    parser.add_argument("--db", default="rag_db")
    parser.add_argument("--user", default="rag")
    parser.add_argument("--timeout", type=float, default=1800.0)
    parser.add_argument("--skip-delete", action="store_true",
                         help="색인 전 기존 perf-test 문서 삭제를 건너뛴다(이미 빈 상태를 직접 확인한 경우)")
    args = parser.parse_args()

    try:
        if not args.skip_delete:
            delete_existing(args.container, args.db, args.user)
        result = run_benchmark(args.base_url.rstrip("/"), args.markdown_root, args.size,
                                args.container, args.db, args.user, args.timeout)
    except BenchmarkError as e:
        print(f"실패: {e}")
        result = {"size": args.size, "success": False, "error": str(e)}

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    if result.get("success"):
        print(f"[{args.size}] 성공: {result['elapsed_ms']/1000:.1f}초, "
              f"문서 {result['db_document_count']}개, 청크 {result['db_chunk_count']}개, "
              f"{result['documents_per_minute']:.1f} docs/min, {result['chunks_per_second']:.2f} chunks/sec")
        if not result["counts_match"]:
            print("경고: API 응답 카운트와 DB 실제 카운트가 다릅니다 — 확인 필요")
    else:
        print(f"[{args.size}] 실패 또는 부분 성공 — {result}")
    print(f"결과 저장 → {args.out}")


if __name__ == "__main__":
    main()
