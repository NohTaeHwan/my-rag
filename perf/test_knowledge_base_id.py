#!/usr/bin/env python3
"""perf 호출자 4곳과 래퍼 2곳이 필수 knowledgeBaseId를 명시 입력으로 받는지 검증하는 오프라인 테스트.

외부 네트워크·Spark·DB를 쓰지 않는다. Python 평가 스크립트는 urlopen을 대체하고,
래퍼는 PATH에 가짜 docker를 둬서 인자만 기록하며, k6 스크립트는 loopback(127.0.0.1) 임시
stub 서버에 1회만 요청을 보내 URL·본문을 확인한다(k6가 없으면 해당 테스트만 skip). k6 테스트는
임시 디렉터리의 스크립트 사본과 최소 fixture 데이터셋만 쓰므로 실제(gitignore된) 데이터셋이 없어도 된다.

실행:
  python3 perf/test_knowledge_base_id.py
"""
from __future__ import annotations

import io
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from unittest import mock

PERF_DIR = Path(__file__).resolve().parent
LOAD_DIR = PERF_DIR / "load"
sys.path.insert(0, str(PERF_DIR / "evaluation"))

import evaluate_answers  # noqa: E402
import evaluate_retrieval  # noqa: E402

INVALID_IDS = ["", "0", "-1", "abc", "1.5", " 1", "01"]
# search-load.js의 질의 버킷(gold/paraphrase/keyword_identifier/unanswerable)을 하나씩 채우는 최소 데이터셋.
FIXTURE_QUERIES = [
    {"category": "direct_lookup", "question": "fixture-direct"},
    {"category": "paraphrase", "question": "fixture-paraphrase"},
    {"category": "keyword_identifier", "question": "fixture-keyword"},
    {"category": "unanswerable", "question": "fixture-unanswerable"},
]
FIXTURE_QUESTIONS = {q["question"] for q in FIXTURE_QUERIES}


class FakeResponse(io.BytesIO):
    status = 200

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class PythonEvaluatorTest(unittest.TestCase):
    def test_search_sends_knowledge_base_id_query_param(self):
        with mock.patch.object(evaluate_retrieval.urllib.request, "urlopen",
                               return_value=FakeResponse(b'{"results": []}')) as urlopen:
            evaluate_retrieval.search("http://x", "환불", 7, 1.0)
        params = urllib.parse.parse_qs(urllib.parse.urlparse(urlopen.call_args.args[0]).query)
        self.assertEqual(params, {"query": ["환불"], "knowledgeBaseId": ["7"]})

    def test_answer_sends_knowledge_base_id_in_body(self):
        with mock.patch.object(evaluate_answers.urllib.request, "urlopen",
                               return_value=FakeResponse(b'{"answer": "a", "sources": []}')) as urlopen:
            evaluate_answers.call_answer("http://x", "환불", 7, 1.0)
        body = json.loads(urlopen.call_args.args[0].data)
        self.assertEqual(body, {"question": "환불", "knowledgeBaseId": 7})

    def test_cli_rejects_missing_or_invalid_id_before_network(self):
        for module in (evaluate_retrieval, evaluate_answers):
            base = ["prog", "--base-url", "http://x", "--queries", "q.jsonl", "--out", "o.json"]
            for extra in ([], *(["--knowledge-base-id", v] for v in INVALID_IDS)):
                with self.subTest(module=module.__name__, extra=extra), \
                        mock.patch.object(module.urllib.request, "urlopen") as urlopen, \
                        mock.patch.object(sys, "argv", base + extra), \
                        mock.patch("sys.stderr", new_callable=io.StringIO), \
                        self.assertRaises(SystemExit) as ctx:
                    module.main()
                self.assertEqual(ctx.exception.code, 2)
                urlopen.assert_not_called()


class WrapperTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.log = self.tmp / "docker.log"
        fake = self.tmp / "docker"
        fake.write_text(f'#!/bin/sh\necho "$@" >> "{self.log}"\n')
        fake.chmod(0o755)
        self.env = {**os.environ, "PATH": f"{self.tmp}:{os.environ['PATH']}"}
        self.env.pop("KNOWLEDGE_BASE_ID", None)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def run_wrapper(self, name, kb):
        env = dict(self.env)
        if kb is not None:
            env["KNOWLEDGE_BASE_ID"] = kb
        return subprocess.run(["bash", str(LOAD_DIR / name), "http://127.0.0.1:1"],
                              env=env, capture_output=True, text=True)

    def test_wrappers_pass_knowledge_base_id_to_k6(self):
        for name in ("run_search_load.sh", "run_answer_load.sh"):
            with self.subTest(name=name):
                self.log.unlink(missing_ok=True)
                self.assertEqual(self.run_wrapper(name, "3").returncode, 0)
                lines = self.log.read_text().splitlines()
                self.assertTrue(lines)
                self.assertTrue(all("-e KNOWLEDGE_BASE_ID=3 " in line for line in lines), lines)

    def test_wrappers_fail_before_docker_on_missing_or_invalid_id(self):
        for name in ("run_search_load.sh", "run_answer_load.sh"):
            for kb in [None, *INVALID_IDS]:
                with self.subTest(name=name, kb=kb):
                    self.log.unlink(missing_ok=True)
                    self.assertNotEqual(self.run_wrapper(name, kb).returncode, 0)
                    self.assertFalse(self.log.exists())


class Recorder(BaseHTTPRequestHandler):
    requests: list = []

    def _reply(self, body):
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        Recorder.requests.append(("GET", self.path, None))
        self._reply(b'{"results": []}')

    def do_POST(self):
        data = self.rfile.read(int(self.headers["Content-Length"]))
        Recorder.requests.append(("POST", self.path, json.loads(data)))
        self._reply(b'{"answer": "a", "sources": []}')

    def log_message(self, *args):
        pass


@unittest.skipUnless(shutil.which("k6"), "k6 미설치")
class K6ScriptTest(unittest.TestCase):
    """k6 스크립트·scenarios.json 사본과 최소 fixture 데이터셋을 임시 디렉터리에 두고 실행한다 —
    gitignore된 실제 데이터셋(perf/dataset/*.resolved.jsonl)이 없는 새 clone에서도 동작하고, 원본은 건드리지 않는다."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.load_dir = self.tmp / "load"
        self.load_dir.mkdir()
        for name in ("search-load.js", "answer-load.js", "scenarios.json"):
            shutil.copy(LOAD_DIR / name, self.load_dir / name)
        (self.tmp / "dataset").mkdir()
        (self.tmp / "dataset" / "queries_m.resolved.jsonl").write_text(
            "".join(json.dumps(q) + "\n" for q in FIXTURE_QUERIES), encoding="utf-8")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def k6(self, script, kb, base_url="http://127.0.0.1:1"):
        env = {k: v for k, v in os.environ.items() if k != "KNOWLEDGE_BASE_ID"}
        env.update({"BASE_URL": base_url, "STAGE": "smoke", "K6_NO_USAGE_REPORT": "true"})
        if kb is not None:
            env["KNOWLEDGE_BASE_ID"] = kb
        return subprocess.run(["k6", "run", "--quiet", "--vus", "1", "--iterations", "1", script],
                              cwd=self.load_dir, env=env, capture_output=True, text=True, timeout=60)

    def test_scripts_fail_in_init_on_missing_or_invalid_id(self):
        for script in ("search-load.js", "answer-load.js"):
            for kb in [None, *INVALID_IDS]:
                with self.subTest(script=script, kb=kb):
                    result = self.k6(script, kb)
                    self.assertNotEqual(result.returncode, 0)
                    self.assertIn("KNOWLEDGE_BASE_ID", result.stdout + result.stderr)

    def test_scripts_send_knowledge_base_id(self):
        server = HTTPServer(("127.0.0.1", 0), Recorder)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base_url = f"http://127.0.0.1:{server.server_port}"
        try:
            Recorder.requests = []
            self.assertEqual(self.k6("search-load.js", "5", base_url).returncode, 0)
            self.assertEqual(self.k6("answer-load.js", "5", base_url).returncode, 0)
        finally:
            server.shutdown()
            server.server_close()
        (m1, path, _), (m2, path2, body) = Recorder.requests
        self.assertEqual((m1, urllib.parse.urlparse(path).path), ("GET", "/api/search"))
        params = urllib.parse.parse_qs(urllib.parse.urlparse(path).query)
        self.assertEqual(params["knowledgeBaseId"], ["5"])
        self.assertIn(params["query"][0], FIXTURE_QUESTIONS)  # 실제 데이터셋이 아닌 임시 fixture를 썼는지
        self.assertEqual((m2, path2), ("POST", "/api/answers"))
        self.assertEqual(body["knowledgeBaseId"], 5)


if __name__ == "__main__":
    unittest.main()
