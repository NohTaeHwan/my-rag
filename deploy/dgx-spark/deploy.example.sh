#!/usr/bin/env bash
# EXAMPLE — Spark 실접속 방식 확정 후 실제 값으로 채워서 사용한다.
# GitHub Actions cd.yml이 SSH로 이 스크립트를 실행한다고 가정한다.
# 이미지는 로컬 전송이 아니라 GHCR(ghcr.io/nohtaehwan/my-rag)에서 직접 pull한다.
# Docker Compose 파일: deploy/dgx-spark/docker-compose.app.yml 참고.
#
# 실행 전 사람이 직접 확인할 것:
#   - <deploy-directory>, <app-health-url> 이 실제 값으로 치환되었는지
set -euo pipefail

DEPLOY_DIR="<deploy-directory>"
COMPOSE_FILE="${DEPLOY_DIR}/compose/docker-compose.app.yml"
IMAGE="ghcr.io/nohtaehwan/my-rag:latest"
HEALTH_URL="<app-health-url>"

# 롤백용으로 현재 이미지를 previous 태그로 보존
CURRENT_ID=$(docker compose -f "${COMPOSE_FILE}" images -q my-rag-app 2>/dev/null || true)
if [ -n "${CURRENT_ID}" ]; then
  docker tag "${CURRENT_ID}" my-rag:previous
fi

docker compose -f "${COMPOSE_FILE}" pull
docker compose -f "${COMPOSE_FILE}" up -d

# 컨테이너가 기동할 시간을 준다. 값은 실제 기동 시간 측정 후 조정한다.
sleep 5

if curl -fsS "${HEALTH_URL}" > /dev/null; then
  echo "배포 성공"
else
  echo "health check 실패 — 이전 이미지로 롤백" >&2
  if docker image inspect my-rag:previous > /dev/null 2>&1; then
    docker tag my-rag:previous "${IMAGE}"
    docker compose -f "${COMPOSE_FILE}" up -d
  fi
  exit 1
fi
