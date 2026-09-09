#!/usr/bin/env bash
# EXAMPLE — Spark 실접속 방식 확정 후 실제 값으로 채워서 사용한다.
# 이 스크립트는 Spark 위에서, GitHub Actions cd.yml이 새 JAR을
# <deploy-directory>/app/my-rag.jar.new 로 올려놓은 뒤에 실행된다고 가정한다.
#
# 실행 전 사람이 직접 확인할 것:
#   - <deploy-directory>, EnvironmentFile 경로가 실제 값으로 치환되었는지
#   - systemd 서비스명이 my-rag가 맞는지
set -euo pipefail

DEPLOY_DIR="<deploy-directory>"
APP_DIR="${DEPLOY_DIR}/app"
NEW_JAR="${APP_DIR}/my-rag.jar.new"
CURRENT_JAR="${APP_DIR}/my-rag.jar"
BACKUP_JAR="${APP_DIR}/my-rag.jar.bak"
SERVICE_NAME="my-rag"

if [ ! -f "${NEW_JAR}" ]; then
  echo "새 JAR이 없습니다: ${NEW_JAR}" >&2
  exit 1
fi

if [ -f "${CURRENT_JAR}" ]; then
  cp "${CURRENT_JAR}" "${BACKUP_JAR}"
fi

mv "${NEW_JAR}" "${CURRENT_JAR}"

sudo systemctl restart "${SERVICE_NAME}"

# systemd가 기동할 시간을 준다. 값은 실제 기동 시간 측정 후 조정한다.
sleep 5

if curl -fsS http://127.0.0.1:8080/health > /dev/null; then
  echo "배포 성공"
else
  echo "health check 실패 — 이전 JAR로 롤백" >&2
  if [ -f "${BACKUP_JAR}" ]; then
    cp "${BACKUP_JAR}" "${CURRENT_JAR}"
    sudo systemctl restart "${SERVICE_NAME}"
  fi
  exit 1
fi
