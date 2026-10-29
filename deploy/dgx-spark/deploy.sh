#!/usr/bin/env bash
# 실제 배포 스크립트. GitHub Actions cd.yml이 이 파일을 Spark로 scp한 뒤 SSH로 실행한다
# (즉 이 저장소에 있는 내용이 곧 Spark에서 실행되는 내용이다 — 별도로 손으로 동기화할 필요 없음).
# 이미지는 GHCR(ghcr.io/nohtaehwan/my-rag)의 커밋 SHA 태그를 pull한다(latest 미사용).
# Docker Compose 파일: deploy/dgx-spark/docker-compose.app.yml (Spark의 compose/ 디렉터리에 위치).
# Spark의 Compose 파일은 수정하지 않는다 — 배포 때마다 image 한 항목만 덮는 임시 override 파일을
# 함께 넘긴다(env_file·volumes·network 등 나머지 설정은 Spark 파일 그대로).
#
# 필수 환경변수 (cd.yml이 SSH 실행 시 주입, 로컬에서 수동 실행할 때도 직접 지정):
#   DEPLOY_DIR — Spark 배포 루트 디렉터리 (예: /home/<user>/services/my-rag)
#   HEALTH_URL — 배포 후 확인할 헬스체크 URL (예: http://127.0.0.1:8090/health)
#   DEPLOY_SHA — 배포할 커밋 SHA(소문자 40자리 hex). CI가 발행한 ghcr.io/nohtaehwan/my-rag:<SHA> 이미지.
#
# 주의: 이 스크립트로 배포한 뒤 Spark에서 override 없이 `docker compose up`을 직접 실행하면
# Compose 파일의 image(latest 등)로 바뀐다. 수동 재기동도 이 스크립트를 통해 SHA를 지정한다.
set -euo pipefail

: "${DEPLOY_DIR:?DEPLOY_DIR 환경변수가 필요합니다}"
: "${HEALTH_URL:?HEALTH_URL 환경변수가 필요합니다}"
: "${DEPLOY_SHA:?DEPLOY_SHA 환경변수가 필요합니다(커밋 SHA 40자리)}"
if ! [[ "${DEPLOY_SHA}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "DEPLOY_SHA 형식 오류 — 소문자 40자리 hex 커밋 SHA만 허용" >&2
  exit 1
fi

COMPOSE_FILE="${DEPLOY_DIR}/compose/docker-compose.app.yml"
IMAGE="ghcr.io/nohtaehwan/my-rag:${DEPLOY_SHA}"
OVERRIDE=$(mktemp "${TMPDIR:-/tmp}/my-rag-override.XXXXXX")
trap 'rm -f "${OVERRIDE}"' EXIT

# Spark Compose 위에 my-rag-app의 image만 바꿔 실행한다.
compose_with_image() {
  local image=$1; shift
  printf 'services:\n  my-rag-app:\n    image: %s\n' "${image}" > "${OVERRIDE}"
  docker compose -f "${COMPOSE_FILE}" -f "${OVERRIDE}" "$@"
}

# 롤백용으로 현재 실행 중인 이미지를 로컬 previous 태그로 보존(SHA 태그는 건드리지 않는다)
CURRENT_ID=$(docker compose -f "${COMPOSE_FILE}" images -q my-rag-app 2>/dev/null || true)
if [ -n "${CURRENT_ID}" ]; then
  docker tag "${CURRENT_ID}" my-rag:previous
fi

# pull이 실패하면(이미지 미존재 등) set -e로 up 전에 중단한다.
compose_with_image "${IMAGE}" pull my-rag-app
compose_with_image "${IMAGE}" up -d my-rag-app

# 컨테이너가 기동할 시간을 준다. 값은 실제 기동 시간 측정 후 조정한다.
sleep 5

if curl -fsS "${HEALTH_URL}" > /dev/null; then
  echo "배포 성공: ${IMAGE}"
else
  echo "health check 실패 — 이전 이미지(my-rag:previous)로 재기동" >&2
  if docker image inspect my-rag:previous > /dev/null 2>&1; then
    compose_with_image my-rag:previous up -d my-rag-app
  fi
  exit 1
fi
