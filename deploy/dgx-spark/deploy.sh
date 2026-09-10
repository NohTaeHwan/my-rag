#!/usr/bin/env bash
# 실제 배포 스크립트. GitHub Actions cd.yml이 이 파일을 Spark로 scp한 뒤 SSH로 실행한다
# (즉 이 저장소에 있는 내용이 곧 Spark에서 실행되는 내용이다 — 별도로 손으로 동기화할 필요 없음).
# 이미지는 GHCR(ghcr.io/nohtaehwan/my-rag)에서 pull한다.
# Docker Compose 파일: deploy/dgx-spark/docker-compose.app.yml (Spark의 compose/ 디렉터리에 위치).
#
# 필수 환경변수 (cd.yml이 SSH 실행 시 주입, 로컬에서 수동 실행할 때도 직접 지정):
#   DEPLOY_DIR — Spark 배포 루트 디렉터리 (예: /home/<user>/services/my-rag)
#   HEALTH_URL — 배포 후 확인할 헬스체크 URL (예: http://127.0.0.1:8090/health)
set -euo pipefail

: "${DEPLOY_DIR:?DEPLOY_DIR 환경변수가 필요합니다}"
: "${HEALTH_URL:?HEALTH_URL 환경변수가 필요합니다}"

COMPOSE_FILE="${DEPLOY_DIR}/compose/docker-compose.app.yml"
IMAGE="ghcr.io/nohtaehwan/my-rag:latest"

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
