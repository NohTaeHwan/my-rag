#!/usr/bin/env python3
"""
기능별 코드 검수 가이드 — 분석 스크립트
변경 파일·컴파일·테스트 결과를 받아 검수 분석 JSON을 stdout으로 출력합니다.
"""
import sys
import json
import argparse
import subprocess
import re
import os
import fnmatch


# ── 규칙 로드 ────────────────────────────────────────────────────────────────

def load_rules(rules_file):
    with open(rules_file, encoding='utf-8') as f:
        return json.load(f)


# ── 파일 패턴 매칭 (** 지원 glob) ────────────────────────────────────────────

def _glob_match(pattern, path):
    """** 를 포함한 glob 패턴을 정규식으로 변환해 매칭합니다."""
    if '**' not in pattern:
        return fnmatch.fnmatch(path, pattern)

    parts = pattern.split('**')
    regex = ''
    for i, part in enumerate(parts):
        # * → [^/]*, ? → [^/], 나머지 특수문자 이스케이프
        escaped = re.escape(part)
        escaped = escaped.replace(r'\*', '[^/]*').replace(r'\?', '[^/]')
        if i == 0:
            regex += escaped
        else:
            regex += '.*' + escaped

    return bool(re.match('^' + regex + '$', path))


def match_file_patterns(file_path, patterns):
    """파일 경로가 filePatterns 중 하나라도 매칭되는지 확인합니다."""
    for pattern in patterns:
        if _glob_match(pattern, file_path):
            return True
    return False


# ── diff 파싱 ─────────────────────────────────────────────────────────────────

def _build_line_map(diff_text):
    """
    diff 텍스트에서 새 파일 기준 라인 번호 → 내용 매핑을 구성합니다.
    추가된 줄(+)과 컨텍스트 줄( ) 모두 포함합니다.
    """
    line_map = {}
    current_new_line = 0

    for line in diff_text.splitlines():
        hunk = re.match(r'^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@', line)
        if hunk:
            current_new_line = int(hunk.group(1)) - 1
            continue

        if line.startswith('+++') or line.startswith('---'):
            continue

        if line.startswith('+'):
            current_new_line += 1
            line_map[current_new_line] = line[1:]
        elif line.startswith('-'):
            pass  # 삭제 줄은 new line 번호에 영향 없음
        elif not line.startswith('\\'):
            current_new_line += 1
            line_map[current_new_line] = line[1:] if len(line) > 0 else ''

    return line_map


def parse_diff_hunks(diff_text):
    """
    diff에서 추가된 줄(+)만 추출합니다.
    반환: list of (line_number, content)
    """
    added = []
    current_new_line = 0

    for line in diff_text.splitlines():
        hunk = re.match(r'^@@ -\d+(?:,\d+)? \+(\d+)(?:,\d+)? @@', line)
        if hunk:
            current_new_line = int(hunk.group(1)) - 1
            continue

        if line.startswith('+++') or line.startswith('---'):
            continue

        if line.startswith('+'):
            current_new_line += 1
            added.append((current_new_line, line[1:]))
        elif line.startswith('-'):
            pass
        elif not line.startswith('\\'):
            current_new_line += 1

    return added


# ── 심볼 탐색 ─────────────────────────────────────────────────────────────────

_METHOD_PATTERN = re.compile(
    r'(?:public|private|protected|static|final|default|abstract)\s+'
    r'(?:[\w<>\[\],\s]+\s+)?'
    r'(\w+)\s*\('
)


def find_symbol_near_line(diff_text, line_number):
    """
    diff에서 line_number 이후 10줄, 이전 5줄 범위에서 메서드 심볼을 탐색합니다.
    찾지 못하면 None을 반환합니다. 존재하지 않는 심볼을 만들어 반환하지 않습니다.
    """
    line_map = _build_line_map(diff_text)
    if not line_map:
        return None

    max_ln = max(line_map.keys())
    search_range = list(range(line_number, min(line_number + 10, max_ln + 1))) + \
                   list(range(line_number - 1, max(line_number - 5, 0), -1))

    for ln in search_range:
        content = line_map.get(ln, '')
        m = _METHOD_PATTERN.search(content)
        if m:
            return m.group(1)

    return None


# ── 규칙 적용 ─────────────────────────────────────────────────────────────────

def apply_rule(rule, file_path, diff_text):
    """
    단일 규칙을 diff에 적용해 finding 목록을 반환합니다.
    같은 규칙에서 여러 패턴이 매칭돼도 파일당 finding은 하나만 생성합니다.
    """
    if not match_file_patterns(file_path, rule.get('filePatterns', [])):
        return []

    added_lines = parse_diff_hunks(diff_text)
    if not added_lines:
        return []

    for pattern in rule.get('contentPatterns', []):
        try:
            regex = re.compile(pattern)
        except re.error:
            continue

        for line_no, content in added_lines:
            if regex.search(content):
                symbol = find_symbol_near_line(diff_text, line_no)
                finding = {
                    'ruleId': rule['id'],
                    'source': 'hook-rule',
                    'severity': rule['severity'],
                    'file': file_path,
                    'line': line_no,
                    'symbol': symbol if symbol else 'unknown',
                    'reason': rule['reason'],
                    'check': rule['check'],
                }
                return [finding]

    return []


# ── 검수 필요 여부 판단 ───────────────────────────────────────────────────────

def _is_review_required(high_risk_findings, test_result):
    """
    reportRequired=true 규칙(Critical·High)이 탐지됐을 때만 reviewRequired=true.
    "테스트 없음" 단독은 리포트를 트리거하지 않음 — validation.unverified에 기록만 함.
    """
    if high_risk_findings:
        return True
    return False


def _highest_risk(findings):
    for level in ('critical', 'high', 'medium', 'low'):
        if any(f['severity'] == level for f in findings):
            return level
    return 'none'


def _build_unverified(test_result):
    if test_result == 'not-found':
        return ['대응 테스트 없음']
    if test_result == 'not-run':
        return ['테스트 미실행']
    if test_result == 'failed':
        return ['테스트 실패']
    return []


def _unique_in_order(items, key=None):
    """항목의 최초 등장 순서를 유지하며 중복을 제거합니다."""
    seen = set()
    unique = []
    for item in items:
        identity = key(item) if key else item
        if identity in seen:
            continue
        seen.add(identity)
        unique.append(item)
    return unique


# ── 핵심 분석 함수 (단위 테스트에서 직접 호출 가능) ───────────────────────────

def analyze(rules, changed_files, diff_by_file, compile_result, test_result, executed_tests):
    """
    Args:
        rules:          list of rule dicts (review-rules.json 내용)
        changed_files:  list of relative file paths
        diff_by_file:   dict mapping file_path → diff text
        compile_result: "passed" | "failed"
        test_result:    "passed" | "failed" | "not-found" | "not-run"
        executed_tests: list of executed test class names

    Returns:
        dict (JSON-serializable 분석 결과)
    """
    changed_files = _unique_in_order(changed_files)
    executed_tests = _unique_in_order(executed_tests)
    reportable_ids = {r['id'] for r in rules if r.get('reportRequired', False)}

    findings = []
    for file_path in changed_files:
        diff_text = diff_by_file.get(file_path, '')
        if not diff_text:
            continue
        for rule in rules:
            findings.extend(apply_rule(rule, file_path, diff_text))

    findings = _unique_in_order(
        findings,
        key=lambda finding: (
            finding['ruleId'],
            finding['file'],
            finding['line'],
            finding['symbol'],
        ),
    )

    high_risk = [f for f in findings if f['ruleId'] in reportable_ids]
    review_required = _is_review_required(high_risk, test_result)

    return {
        'reviewRequired': review_required,
        'highestRisk': _highest_risk(high_risk) if review_required else _highest_risk(findings),
        'changedFiles': changed_files,
        'findings': findings,
        'validation': {
            'compile': compile_result,
            'tests': test_result,
            'executedTests': executed_tests,
            'unverified': _build_unverified(test_result),
        },
    }


# ── git diff 수집 ─────────────────────────────────────────────────────────────

def get_diff_for_file(project_root, rel_path):
    """
    git diff HEAD 또는 staged diff를 가져옵니다.
    추적되지 않는 신규 파일은 파일 전체를 pseudo-diff로 구성합니다.
    """
    abs_path = os.path.join(project_root, rel_path) if not os.path.isabs(rel_path) \
               else rel_path

    def _run(cmd):
        try:
            r = subprocess.run(cmd, cwd=project_root, capture_output=True, text=True)
            return r.stdout
        except Exception:
            return ''

    diff = _run(['git', 'diff', 'HEAD', '--', rel_path])
    if not diff:
        diff = _run(['git', 'diff', '--cached', '--', rel_path])
    if not diff and os.path.isfile(abs_path):
        try:
            with open(abs_path, encoding='utf-8', errors='replace') as f:
                lines = f.readlines()
            header = f'--- /dev/null\n+++ b/{rel_path}\n@@ -0,0 +1,{len(lines)} @@\n'
            diff = header + ''.join('+' + ln for ln in lines)
        except Exception:
            pass

    return diff


# ── CLI 진입점 ───────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description='코드 검수 가이드 분석 스크립트')
    parser.add_argument('--project-root', required=True)
    parser.add_argument('--changed-files', required=True,
                        help='변경된 파일 절대경로 목록 (줄바꿈 구분)')
    parser.add_argument('--compile-result', required=True,
                        choices=['passed', 'failed'])
    parser.add_argument('--test-result', required=True,
                        choices=['passed', 'failed', 'not-found', 'not-run'])
    parser.add_argument('--executed-tests', default='',
                        help='실행된 테스트 클래스 (쉼표 구분)')
    parser.add_argument('--rules-file', required=True)
    args = parser.parse_args()

    rules = load_rules(args.rules_file)

    project_root = args.project_root.rstrip('/')

    # 절대경로 → 프로젝트 루트 기준 상대경로로 변환
    changed_files = []
    for f in args.changed_files.splitlines():
        if not f:
            continue
        if f.startswith(project_root + '/'):
            changed_files.append(f[len(project_root) + 1:])
        else:
            changed_files.append(f)

    executed_tests = [t.strip() for t in args.executed_tests.split(',') if t.strip()]

    diff_by_file = {f: get_diff_for_file(project_root, f) for f in changed_files}

    result = analyze(rules, changed_files, diff_by_file,
                     args.compile_result, args.test_result, executed_tests)

    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
