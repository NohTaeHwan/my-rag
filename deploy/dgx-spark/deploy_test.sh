#!/usr/bin/env bash
# deploy.sh 로컬 경계 테스트. 실제 docker·curl·Spark·GHCR에 접속하지 않는다 —
# 임시 디렉터리의 가짜 docker/curl/sleep을 PATH 앞에 두고 호출 기록만 검사한다.
#   실행: bash deploy/dgx-spark/deploy_test.sh
set -u

SCRIPT="$(cd "$(dirname "$0")" && pwd)/deploy.sh"
SHA=0123456789abcdef0123456789abcdef01234567
FAILED=0

# 가짜 명령. MOCK_* 환경변수로 결과를 정하고, 호출과 compose override 내용을 $LOG에 남긴다.
make_stubs() {
  local bin=$1
  mkdir -p "$bin"
  cat > "$bin/docker" <<'EOF'
#!/usr/bin/env bash
echo "docker $*" >> "$LOG"
if [ "$1" = compose ]; then
  files=(); prev=""
  for a in "$@"; do [ "$prev" = -f ] && files+=("$a"); prev=$a; done
  case " $* " in
    *" images -q "*) [ -n "${MOCK_CURRENT_ID:-}" ] && echo "$MOCK_CURRENT_ID"; exit 0 ;;
    *" pull"*) exit "${MOCK_PULL_EXIT:-0}" ;;
    *" up "*) for f in "${files[@]:1}"; do echo "override: $(tr '\n' ' ' < "$f" | tr -s ' ')" >> "$LOG"; done; exit 0 ;;
  esac
  exit 0
fi
if [ "$1 $2" = "image inspect" ]; then exit "${MOCK_HAS_PREVIOUS:-0}"; fi
exit 0
EOF
  # shellcheck disable=SC2016  # 가짜 스크립트 본문이라 여기서 확장하지 않는다
  printf '#!/usr/bin/env bash\necho "curl $*" >> "$LOG"\nexit "${MOCK_HEALTH_EXIT:-0}"\n' > "$bin/curl"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$bin/sleep"
  chmod +x "$bin"/*
}

# run <이름> <기대 exit(0|nonzero)> [VAR=값 ...] — deploy.sh를 가짜 환경에서 실행하고 LOG를 남긴다.
run() {
  local name=$1 expect=$2; shift 2
  TMP=$(mktemp -d)
  make_stubs "$TMP/bin"
  export LOG="$TMP/log"; : > "$LOG"
  env -i PATH="$TMP/bin:/usr/bin:/bin" HOME="$TMP" LOG="$LOG" TMPDIR="$TMP" \
    DEPLOY_DIR="$TMP/deploy" HEALTH_URL=http://127.0.0.1:8090/health "$@" \
    bash "$SCRIPT" > "$TMP/out" 2>&1
  local code=$?
  CASE=$name; CASE_FAIL=0
  if { [ "$expect" = 0 ] && [ $code -ne 0 ]; } || { [ "$expect" != 0 ] && [ $code -eq 0 ]; }; then
    fail "exit $code (기대 $expect) — $(tr '\n' ' ' < "$TMP/out")"
  fi
}

fail() { echo "FAIL [$CASE] $1"; FAILED=1; CASE_FAIL=1; }
has() { grep -qF -- "$1" "$LOG" || fail "로그에 '$1' 없음"; }
lacks() { ! grep -qF -- "$1" "$LOG" || fail "로그에 '$1'가 있으면 안 됨"; }
ok() { [ "$CASE_FAIL" = 0 ] && echo "ok   [$CASE]"; }

run "정상 SHA 배포" 0 DEPLOY_SHA=$SHA MOCK_CURRENT_ID=img-current
has "docker tag img-current my-rag:previous"
has "pull my-rag-app"
has "override: services: my-rag-app: image: ghcr.io/nohtaehwan/my-rag:$SHA"
lacks ":latest"
ok

UPPER=$(printf '%s' "$SHA" | tr 'a-f' 'A-F')
for bad in "" latest 0123456 "$UPPER" "$SHA;rm" "${SHA}0" "../$SHA"; do
  run "SHA 거부: '${bad}'" nonzero DEPLOY_SHA="$bad"
  lacks "docker"
  ok
done

run "DEPLOY_SHA 누락" nonzero
lacks "docker"
ok

run "pull 실패 시 up 안 함" nonzero DEPLOY_SHA=$SHA MOCK_PULL_EXIT=1
has "pull my-rag-app"
lacks " up "
ok

run "health 실패 → previous로 재기동" nonzero DEPLOY_SHA=$SHA MOCK_CURRENT_ID=img-current MOCK_HEALTH_EXIT=22
has "override: services: my-rag-app: image: my-rag:previous"
lacks "docker tag my-rag:previous"
[ "$(grep -c ' up ' "$LOG")" = 2 ] || fail "up 2회(배포·롤백) 기대, 실제 $(grep -c ' up ' "$LOG")"
ok

run "health 실패·previous 없음 → 롤백 없이 실패" nonzero DEPLOY_SHA=$SHA MOCK_HEALTH_EXIT=22 MOCK_HAS_PREVIOUS=1
[ "$(grep -c ' up ' "$LOG")" = 1 ] || fail "up 1회 기대, 실제 $(grep -c ' up ' "$LOG")"
ok

run "override 임시 파일 정리" 0 DEPLOY_SHA=$SHA
[ -z "$(find "$TMP" -maxdepth 1 -name 'my-rag-override*')" ] || fail "override 임시 파일이 남음"
ok

if [ "$FAILED" = 0 ]; then echo "ALL PASSED"; else echo "SOME FAILED"; exit 1; fi
