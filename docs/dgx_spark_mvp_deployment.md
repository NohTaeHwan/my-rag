# DGX Spark MVP 배포 문서

> 상태(2026-09-10): **Task 3~11 전부 PASS — 계획서의 배포 검증 절차 완료.** Postgres·하드웨어/Java17·
> Embedding·LLM·Docker 배포(GHCR pull, ARM64 빌드 수정)·재시작 정책·E2E 검증 데이터·검색/답변/장애
> 시나리오·운영 최소 점검까지 전부 확인. CD 자동화도 완성 — `ci.yml` 성공 시 `workflow_run`으로
> `cd.yml` 자동 트리거, `prd-spark` Environment의 Required reviewer 승인(처음엔 설정이 빠져있던 걸
> 발견해 수정) 이후 Tailscale 연결·SSH·`deploy.sh`(pull+up+헬스체크)까지 자동 실행 확인. 과정에서 실제
> 버그 2건(헬스체크 미처리 예외, DB 타임아웃 30초) 발견·수정·재배포·재검증 완료, `my-rag-app`이 모든
> 인터페이스에 열려 있다는 것도 확인(§향후 보안 개선 항목 — HTTPS/도메인/인증과 함께 나중에 처리하기로 결정).
>
> **코드리뷰 반영 (2026-09-10)**: `deploy/dgx-spark/deploy.example.sh`(placeholder 포함)를 Spark에
> 수동으로 복사·치환해 쓰던 방식에서, **`deploy/dgx-spark/deploy.sh`를 실제 실행 스크립트로 전환**해
> `cd.yml`이 매 배포마다 이 파일을 Spark로 scp해서 덮어쓰도록 바꿨다(`DEPLOY_DIR`/`HEALTH_URL`은
> 환경변수로 주입, placeholder 제거). 이제 저장소가 실제 배포 스크립트의 유일한 원본이다. Task 3
> 체크리스트가 실제로는 통과했는데 문서에 UNVERIFIED로 남아있던 것도 함께 바로잡음.
> 원본 계획: `.hermes/plans/2026-09-08_161535-dgx-spark-mvp-deployment.md`
> 실제 Spark 사용자명·IP·경로·Secret은 이 문서에 절대 기록하지 않는다. 아래 `<placeholder>`를 실행 시점에만 실제 값으로 치환한다.

## 네트워크 구성

```text
Spring Boot API (127.0.0.1:8090, 필요 시만 외부 노출)
  → PostgreSQL   127.0.0.1:5433 (외부 비공개, 8080/5432는 이 Spark의 다른 컨테이너가 이미 사용 중)
  → Embedding    127.0.0.1:8003 (외부 비공개)
  → LLM          127.0.0.1:8002 (외부 비공개)
```

외부 관리 접근은 SSH tunnel / Tailscale / 방화벽 allowlist 중 하나로만 한다. `0.0.0.0` 바인딩은 방화벽·인증 정책 확인 전에는 사용하지 않는다.

## 배포 디렉터리

이 Spark 박스는 사용자 홈 아래를 사용하기로 결정했다(`/opt` 대신):

```text
~/services/my-rag/app/             # JAR
~/services/my-rag/config/          # 운영 EnvironmentFile (권한 600)
~/services/my-rag/logs/
~/services/my-rag/data/markdown/   # 색인 대상 Markdown
~/services/my-rag/compose/         # docker-compose.yml
```

## 이 Spark 박스의 포트 현황 (2026-09-09 `sudo ss -lntp` 전수 확인)

> **중요**: 이 Spark는 my-rag 전용 장비가 아니라, 다른 여러 프로젝트(교회 웹사이트, 트레이딩 봇, hermes-professor,
> honcho API, qdrant, syncthing 등 20개 이상 컨테이너)가 이미 함께 돌아가는 공용 개인 서버다. 포트를
> 추측하지 말고 항상 `sudo ss -lntp`로 실제 사용 중인 포트를 먼저 확인한다.

이미 사용 중이라 우리가 쓸 수 없는 포트(발견된 것들):
```text
5432  → graphrag-db (다른 프로젝트의 pgvector, 우리 DB와 충돌 원인이었음)
8080  → church_web
8081  → webdav-obsidian
8000/8001/8002/8003 → sglang-qwen / vllm-proxy / bge-m3-embedding (8003만 우리가 실제 쓰는 서버)
6333  → qdrant-obsidian
6768/6767 → 내부 프로세스
8384/21027/22000 → syncthing
8642/9119 → hermes-professor
18081 → church_proxy
10022 → sshd (SSH 접속용, 건드리지 않음)
```

그래서 my-rag는 다음 포트를 쓴다(이 박스 한정, 다른 환경과 다를 수 있음):
```text
PostgreSQL (rag-postgres)  127.0.0.1:5433  (docker-compose.yml의 DB_HOST_PORT로 지정)
Spring Boot API            127.0.0.1:8090  (.env/EnvironmentFile의 SERVER_PORT로 지정)
Embedding (기존 서버 재사용) 127.0.0.1:8003
LLM (기존 서버 재사용)       127.0.0.1:8002
```

## Secret 원칙

- `DB_PASSWORD`, `LLM_API_KEY`는 Git에 기록하지 않는다.
- 운영 환경변수는 systemd `EnvironmentFile`(권한 600) 또는 배포 시 shell 환경변수로만 주입한다.
- `application.yml`, README, systemd unit, 로그에 실제 Secret을 남기지 않는다.

---

## Task 3 — Spark에 PostgreSQL + pgvector 실행

```bash
mkdir -p ~/services/my-rag/{app,config,logs,data/markdown,compose}
```

맥북에서 `docker-compose.yml` 복사:
```bash
scp -i ~/.ssh/my_rag_deploy_key -P 10022 docker-compose.yml <spark-user>@<spark-host>:~/services/my-rag/compose/
```

Spark에서 (5432는 다른 프로젝트가 이미 쓰고 있어 `DB_HOST_PORT=5433` 지정 필수):
```bash
cd ~/services/my-rag/compose
cat > .env <<'ENVEOF'
DB_PASSWORD=<원하는 비밀번호>
DB_HOST_PORT=5433
ENVEOF
chmod 600 .env
docker compose up -d rag-db
docker compose ps
```

`docker compose` 명령에 permission denied가 나면 `thnoh93`이 `docker` 그룹에 없는 것 — `sudo usermod -aG docker <spark-user> && newgrp docker`로 해결(2026-09-09 실제로 겪은 문제).

**검증**
```bash
docker exec rag-postgres pg_isready -U rag -d rag_db
docker exec rag-postgres psql -U rag -d rag_db -c "SELECT extname FROM pg_extension WHERE extname = 'vector';"
```

- [x] `docker compose ps`에서 rag-postgres healthy
- [x] `pg_isready` → accepting connections
- [x] vector extension 존재
- [x] 5432가 아닌 5433에 바인딩됨 (`127.0.0.1:5433->5432/tcp` 확인)

실행 결과: **PASS** (2026-09-09, Task 9 문서 색인·Task 10 검색/답변에서 계속 정상 사용 확인됨)

---

## Task 4 — 하드웨어·OS·런타임 사전 점검

```bash
uname -m
cat /etc/os-release
nvidia-smi
nvidia-container-cli info
java -version
docker --version
docker compose version
free -h
df -h
```

- [x] `uname -m` 결과가 실제 아키텍처와 일치 — `aarch64`
- [x] NVIDIA GPU/드라이버 표시됨 — `NVIDIA GB10`, Driver 580.173.02, CUDA 13.0
- [x] Java 17 사용 가능 — 2026-09-09 sudo 승인 후 `apt install openjdk-17-jdk`로 설치, `/usr/lib/jvm/java-17-openjdk-arm64/bin/java -version` → `17.0.20` 확인. (참고: 이후 Docker 배포로 전환하면서 호스트 Java는 실제로는 불필요해짐 — 이미지 안에 JDK 17이 포함됨. 설치 자체는 무해하게 남겨둠)
- [x] Docker daemon 실행 중 — Docker 29.2.1, Compose v5.0.2
- [x] NVIDIA Container Runtime 사용 가능 여부 확인 — `nvidia-container-cli info` 정상 (Model: NVIDIA GB10)
- [x] 모델·볼륨용 디스크 여유 확인 — `/` 3.7T 중 3.2T 여유(11% 사용)
- [x] 메모리/GPU 메모리 여유 기록 — 시스템 메모리 121Gi 중 가용 22Gi (GPU에서 기존 `sglang::scheduler` 프로세스가 87GB 사용 중이라 여유가 그만큼 줄어든 상태, 통합 메모리 아키텍처라 CPU/GPU 메모리 풀 공유). Swap 15Gi 중 4.8Gi 사용

OS: Ubuntu 24.04.4 LTS (Noble)

실행 결과: **PASS** (2026-09-09 실행, Java 17 설치 완료로 블로커 해소)

문제 발생 시 NVIDIA 드라이버를 임의 교체하지 않는다. DGX Spark 기본 이미지·DGX OS·CUDA 조합을 먼저 확인한다.

---

## Task 5 — BGE-M3 Embedding API 실행

> 2026-09-09 확인: 이 Spark 박스에는 이미 Embedding 서버(sglang 기반, `BAAI/bge-m3`)가 **8003번 포트**에서
> 실행 중이며 `127.0.0.1`/Tailscale IP 양쪽에 바인딩되어 있다. `curl` 검증으로 1024차원 벡터 응답을
> 실제로 확인했다(PASS). 누가/언제 이 프로세스를 띄웠는지는 별도 확인이 필요하지만, 새로 설치할 필요는 없다.
> 아래 실행 절차는 이 서버가 없거나 재기동이 필요한 경우를 위한 참고용으로 남겨둔다.

**결정 순서**
1. Spark에 vLLM 기설치 여부 확인
2. ARM64·CUDA 호환 NGC/Docker 이미지 확인
3. vLLM 미지원 시 Sentence Transformers/FlagEmbedding 기반 HTTP API 검토
4. 미지원 설치 명령 반복 실행 금지

**Preflight**
```bash
python3 --version
python3 -m pip show vllm
python3 -c 'import torch; print(torch.__version__); print(torch.cuda.is_available())'
```

**실행 예시**
```bash
vllm serve BAAI/bge-m3 \
  --task embed \
  --host 127.0.0.1 \
  --port 8003
```

**검증**
```bash
curl -fsS http://127.0.0.1:8003/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model":"BAAI/bge-m3","input":"결제 완료 후 주문을 취소하려면?"}' \
  | python3 -c 'import json,sys; x=json.load(sys.stdin); print(len(x["data"][0]["embedding"]))'
```

- [x] HTTP 200
- [x] `data[0].embedding` 존재, 길이 1024 — 실제 curl로 확인
- [ ] 한국어 문장 반복 호출 성공 — 1회만 확인, 반복 호출 미검증
- [ ] 모델 로딩 오류 없음 — 서버 자체 로그 미확인(기존에 떠 있던 프로세스라 로딩 로그 접근 안 함)
- [ ] 8003 포트가 외부 전체 인터페이스에 노출되지 않음 — **확인 필요**: `ss -lntp` 결과 `127.0.0.1`뿐 아니라 Tailscale IP(`100.90.113.121`)에도 바인딩되어 있음. `0.0.0.0` 전체 노출은 아니지만 계획서 권장(loopback 전용)보다는 넓게 열려 있는 상태

실행 결과: **PASS** (2026-09-09, 벡터 길이 1024 확인) — 단, 포트 바인딩 범위는 계획서 권장보다 넓어서 별도 검토 필요.

---

## Task 6 — LLM API 실행 또는 외부 연결 확인

> 2026-09-09 확인: `POST /v1/chat/completions`가 8002에서 정상 응답(HTTP 200), Bearer 토큰 인증 필요(확인됨).
> `GET /v1/models` 응답으로 실제 서빙 모델 id는 `qwen3.8-27b-sglang` (max_model_len 262144) — 요청의
> `model` 필드는 서버가 검증하지 않고 무시하는 것으로 보임(다른 model 문자열을 보내도 동일 출력).
> `.env`의 `LLM_MODEL` 값은 실제 값과 다를 수 있으니 `/v1/models`로 재확인해 맞출 것.
> **알려진 리스크**: 이 모델은 reasoning 모드가 있어 `reasoning_content`를 먼저 채우고, `max_tokens`가
> 작으면(`finish_reason: length`) 실제 `content`가 빈 문자열로 올 수 있다. 이 경우 앱은 `RagException`(500)으로
> 처리한다(의도된 동작). Task 10 E2E에서 실제 질문으로 재현되는지 확인하고, 필요하면 `LLM_MAX_TOKENS`를
> 올리는 것을 검토한다(모델 튜닝은 이번 배포 범위 밖이라 지금 코드는 변경하지 않음).

```bash
curl -fsS -X POST "${LLM_BASE_URL}/chat/completions" \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "<actual-model-id>",
    "messages": [
      {"role":"system","content":"한국어로 답하라."},
      {"role":"user","content":"테스트에 답하라."}
    ],
    "temperature": 0.0,
    "max_tokens": 32
  }'
```

인증이 필요하면 실제 credential을 명령행에 직접 쓰지 말고 승인된 보호 스크립트로 `Authorization` 헤더를 주입한다.

- [x] HTTP 200
- [ ] `choices[0].message.content` 존재 — `max_tokens` 작을 때 reasoning에 토큰 소모되어 빈 문자열 발생 확인(위 리스크 참고), 충분한 `max_tokens`로 재검증 필요
- [ ] 응답 시간 < read-timeout — 별도 측정 안 함
- [x] API Key 있는 서버는 Authorization 헤더로 호출 — Bearer 토큰 인증 확인됨 (무인증 케이스 아님, 계획서 항목과 반대 케이스로 확인)

실행 결과: **PASS (인증/연결 자체는 정상)**, `content` 빈 응답 리스크는 Task 10에서 재검증 필요

---

## Task 7 — Spring Boot Docker 이미지 빌드 및 Spark 배포

> **아키텍처 변경 (2026-09-09)**: 원래 계획은 JAR + systemd였고, 실제로 그 방식으로 Task 7이
> PASS까지 검증됐었다(포그라운드 실행, `{"database":"UP","status":"UP"}` 확인). 이후 이 Spark 박스에
> 이미 떠 있는 다른 20여 개 서비스가 전부 Docker 컨테이너로 관리되고 있다는 걸 확인하고, 일관성을 위해
> **Docker 컨테이너 배포로 전환**하기로 결정했다. `deploy/dgx-spark/my-rag.service.example`(systemd
> 템플릿)은 삭제했고, 아래가 현재 방식이다.

**설계**
- `Dockerfile`: 멀티스테이지 빌드(`eclipse-temurin:17-jdk`로 빌드 → `eclipse-temurin:17-jre`로 실행). Spark 호스트의 Java 8/17 문제와 무관하게 동작한다.
- 컨테이너는 `network_mode: host`로 띄운다 — 기존에 검증된 `127.0.0.1:5433`(DB)/`8003`(Embedding)/`8002`(LLM) 값을 그대로 재사용하기 위함.
- **이미지 전달은 GitHub Container Registry(ghcr.io) 경유**로 최종 결정(처음엔 `docker save`/`load`+scp로 시작했다가, 레지스트리가 표준적인 방식이라 전환). `ci.yml`이 `main` push마다 `ghcr.io/nohtaehwan/my-rag:latest`와 `:<commit-sha>`를 빌드·푸시한다. Spark는 그 이미지를 그냥 `docker compose pull`로 받는다.
- **아키텍처 버그 발견·수정 (2026-09-09)**: GitHub Actions 러너는 x86_64라 기본 `docker build`는 AMD64 이미지를 만든다. 이걸 ARM64인 Spark에서 실행하면 `exec /opt/java/openjdk/bin/java: exec format error`로 컨테이너가 재시작을 무한 반복한다(실제로 겪음). `ci.yml`에 `docker/setup-qemu-action` + `docker/setup-buildx-action` + `docker/build-push-action`(`platforms: linux/arm64`)을 추가해 명시적으로 ARM64용으로 빌드하도록 고쳤다. 로컬(맥북, Apple Silicon)에서 빌드할 땐 우연히 아키텍처가 맞아서 이 문제가 안 보였다.
- **사전 준비(1회, GitHub 웹에서 수동)**: 저장소 → Packages → `my-rag` 패키지 → Package settings → Visibility를 **Public**으로 변경. 이미지에 secret이 baked-in되지 않으므로 public이어도 안전하고, Spark에서 별도 GHCR 로그인 없이 pull 가능해진다.

**1. 빌드 및 푸시 (로컬, 또는 CI가 자동으로 함)**
```bash
docker build -t ghcr.io/nohtaehwan/my-rag:latest .
docker login ghcr.io -u nohtaehwan   # Personal Access Token(packages:write 권한)으로 로그인
docker push ghcr.io/nohtaehwan/my-rag:latest
```
`main`에 push하면 `ci.yml`이 위 과정을 자동으로 해준다 — 수동 빌드는 긴급 상황에서만 필요하다.

**4. 운영 환경변수 파일** (Spark, `~/services/my-rag/config/my-rag.env` — 이미 Task 7 JAR 검증 때 만든 것 그대로 재사용 가능, 예시 템플릿은 `deploy/dgx-spark/my-rag.env.example` 참고)
```text
DB_URL=jdbc:postgresql://127.0.0.1:5433/rag_db
DB_USERNAME=rag
DB_PASSWORD=<outside repository>
EMBEDDING_BASE_URL=http://127.0.0.1:8003/v1
EMBEDDING_MODEL=BAAI/bge-m3
EMBEDDING_DIMENSION=1024
LLM_BASE_URL=http://127.0.0.1:8002/v1
LLM_MODEL=qwen3.8-27b-sglang
LLM_API_KEY=<optional, outside repository>
SERVER_PORT=8080
```
컨테이너 안에서는 `SERVER_PORT=8080`로 둬도 된다 — `network_mode: host`라 컨테이너의 8080이 곧 호스트의 8080인데, 호스트 8080은 `church_web`이 이미 쓰고 있으므로 **8090으로 지정**해야 한다:
```text
SERVER_PORT=8090
```

**5. Compose 파일 배치 및 실행** (맥북에서 전송)
```bash
scp -i ~/.ssh/my_rag_deploy_key -P 10022 deploy/dgx-spark/docker-compose.app.yml thnoh93@100.90.113.121:~/services/my-rag/compose/
```
Spark에서:
```bash
cd ~/services/my-rag/compose
docker compose -f docker-compose.app.yml pull
docker compose -f docker-compose.app.yml up -d
docker compose -f docker-compose.app.yml ps
```

**검증**
```bash
curl -fsS http://127.0.0.1:8090/health
curl -fsS http://127.0.0.1:8090/v3/api-docs
curl -fsS -I http://127.0.0.1:8090/swagger-ui.html
```

- [x] (JAR 방식으로 사전 검증됨, 2026-09-09) API 프로세스 기동 — 포그라운드 실행, 에러 없음
- [x] (JAR 방식으로 사전 검증됨) Flyway migration 성공, DB 연결 성공 — `{"database":"UP","status":"UP"}`
- [x] (로컬 Docker 빌드) 이미지 빌드 성공, 로컬 컨테이너로 `/health` PASS 확인 (host.docker.internal로 맥북 DB 연결 테스트)
- [x] Spark에서 Docker Compose로 실제 기동 — `docker compose -f docker-compose.app.yml up -d` 후 `{"status":"UP","database":"UP"}` 확인 (ARM64 빌드 수정 후)

실행 결과: **PASS** (2026-09-09, GHCR pull 기반, ARM64 이미지, `~/services/my-rag`, 포트 8090)

---

## Task 8 — 재시작 정책 (Docker Compose `restart: unless-stopped`)

> systemd 대신 `deploy/dgx-spark/docker-compose.app.yml`의 `restart: unless-stopped`가 이 역할을 한다 —
> 컨테이너가 죽으면 Docker가 자동 재시작하고, Docker 데몬 자체는 Spark 부팅 시 systemd가 이미 자동
> 기동하므로(`systemctl is-enabled docker`), 재부팅 후에도 컨테이너가 따라 올라온다.

**확인**
```bash
docker inspect -f '{{.HostConfig.RestartPolicy.Name}}' my-rag-app
systemctl is-enabled docker
```

- [x] `RestartPolicy.Name`이 `unless-stopped`
- [x] `docker` 서비스가 `enabled` (재부팅 시 자동 기동)
- [x] 크래시 재현 후 자동 재시작 확인 — `docker kill`/`docker exec ... kill -9 1`은 Docker가 "사람이 의도적으로 끔"으로
      처리해서 재시작 정책이 발동하지 않음(예상된 동작, 버그 아님). 진짜 크래시를 재현하려면 호스트에서
      `docker inspect -f '{{.State.Pid}}' my-rag-app`로 호스트 PID를 찾아 `sudo kill -9 <pid>`로 직접 죽여야 함
      (컨테이너 프로세스가 root로 떠서 sudo 필요). 이 방식으로 재현 성공 — 자동 재시작 및 `/health` 정상 복구 확인

실행 결과: **PASS** (2026-09-09)

---

## Task 9 — MVP E2E 검증 데이터 준비

최소 테스트 문서 3개 (주문 취소 정책 / 환불 정책 / 배송 정책). 개인정보·Secret·실제 내부 URL을 넣지 않는다. `document.source-directory` 설정과 실제 색인 대상 디렉터리를 일치시킨다.

```bash
curl -fsS -X POST http://127.0.0.1:8090/api/documents/index
docker exec rag-postgres psql -U rag -d rag_db -c 'SELECT COUNT(*) FROM tb_document;'
docker exec rag-postgres psql -U rag -d rag_db -c 'SELECT COUNT(*) FROM tb_document_chunk;'
```

- [x] HTTP 200
- [x] `documentCount`가 테스트 문서 수와 일치 — 3 (주문취소정책/환불정책/배송정책)
- [x] `chunkCount` > 0 — 15
- [x] `embeddingDimension` == 1024

실행 결과: **PASS** (2026-09-10). `docker-compose.app.yml`에 `../data/markdown:/app/data/markdown:ro` 볼륨 마운트를 추가해야 했음(원래 누락되어 있었음 — 컨테이너 안 앱이 호스트 마크다운을 못 보는 상태였던 걸 이번에 발견·수정).

---

## Task 10 — 검색·답변 E2E 검증

**검색**
```bash
curl -fsS --get http://127.0.0.1:8090/api/search \
  --data-urlencode 'query=주문을 취소하려면 어떻게 해야 하나요?'
```
- [x] HTTP 200, distance 오름차순(0.305→0.385), 5개 전부 `주문취소정책.md`에서 정확히 매칭

**답변**
```bash
curl -fsS -X POST http://127.0.0.1:8090/api/answers \
  -H 'Content-Type: application/json' \
  -d '{"question":"환불은 며칠 정도 걸리나요?"}'
```
- [x] HTTP 200, answer 비어있지 않음(실제 답변 정상 생성 — Task 6에서 우려했던 reasoning 모드로 인한
      빈 응답 리스크가 이 질문에서는 재현 안 됨), sources 대부분 `환불정책.md`

**근거 없는 질문**
```bash
curl -fsS -X POST http://127.0.0.1:8090/api/answers \
  -H 'Content-Type: application/json' \
  -d '{"question":"오늘 날씨 어때요?"}'
```
- [x] (수정된 이해) DB에 문서가 이미 있어 벡터 검색은 항상 top-K를 반환하므로 `sources`가 완전히
      비지는 않음(distance가 0.60~0.65로 관련 질문 대비 훨씬 높게 나와 실제로 약한 매칭임은 확인됨).
      대신 LLM이 프롬프트의 grounding 규칙에 따라 "제공된 문서에서 확인할 수 없습니다"로 정확히 응답 —
      **의도된 동작, 버그 아님**. "완전히 무관한 질문"과 "DB 자체가 비어있는 경우"(계획서가 원래 의도한
      케이스)는 다른 시나리오이며, 후자는 지금 상태(문서 3개 색인됨)에서는 재현 불가

**입력 오류**
```bash
curl -i -X POST http://127.0.0.1:8090/api/answers \
  -H 'Content-Type: application/json' \
  -d '{"question":"   "}'
```
- [x] HTTP 400, `{"message":"question은 비어 있을 수 없습니다."}`, 내부 상세 미노출

**장애 시나리오** (운영 데이터 있는 환경에서는 실행하지 않음, 사전 승인 필요)
- Embedding API 중단 후 `/api/search`
- LLM API 중단 후 `/api/answers`
- PostgreSQL 중단 후 `/health`, API 호출

> 아래 각 항목의 실제 검증 결과는 이 하위 3가지(Embedding/LLM 중단 시 예상 동작, PostgreSQL 중단 실제
> 실행)를 다루는 각 섹션에 개별적으로 기록한다. 위 4가지 공통 기준(무한 대기 없음/sanitized 500/
> credential 미노출/로그 추적 가능)은 실행한 PostgreSQL 케이스에서만 실제로 확인했다 — 아래
> "PostgreSQL 장애 (실제 실행)" 섹션 참고.

**주의**: Embedding(8003)/LLM(8002) 컨테이너(`bge-m3-embedding`, `sglang-qwen`)는 my-rag 전용이 아니라
이 Spark의 다른 프로젝트들과 공유되는 것으로 보임(§포트 현황 참고) — 중단 테스트로 껐다 켰다 하면
다른 서비스에 영향 줄 수 있어, **Embedding/LLM 중단 테스트는 실행하지 않기로 결정**. PostgreSQL
(`rag-postgres`)은 my-rag 전용이라 중단 테스트를 실제로 실행함(아래).

### Embedding/LLM 장애 시 예상 동작 (코드 기준 — 실행하지 않고 설계상 예상만 기록, 미검증)

- **Embedding(8003) 장애**: `/api/documents/index`·`/api/search`·`/api/answers`(검색을 거치므로 간접) 영향.
  `embedding.connect-timeout=2s`라 서버 자체가 안 뜬 상태면 약 2초 안에 `RagException` → sanitized
  HTTP 500. 무한 대기 없음(설계상).
- **LLM(8002) 장애**: `/api/answers`만 영향(`/api/search`는 LLM을 안 써서 무관). `llm.connect-timeout=2s`,
  `llm.read-timeout=60s` — 연결 자체가 안 되면 2초, 서버가 응답만 안 주고 있으면 최대 60초까지 대기 가능
  (Embedding보다 대기 시간이 길 수 있음).
- **놓치기 쉬운 점**: `/health`는 애플리케이션+DB만 확인하고 Embedding/LLM은 확인하지 않는다. 즉 Embedding·LLM이
  죽어도 `/health`는 계속 정상(`UP`)으로 보일 수 있어, 이 둘의 장애는 `/health` 모니터링만으로는 못 잡고
  실제 `/api/search`·`/api/answers` 호출로만 드러난다.
- **원인 구분 방법**:
  ```bash
  sudo ss -lntp | grep -E ':(8002|8003)'
  docker logs bge-m3-embedding --tail 50
  docker logs sglang-qwen --tail 50
  docker logs my-rag-app --tail 100   # "Embedding API 호출 실패" / "LLM API 호출 실패" 로그 확인
  ```

### PostgreSQL 장애 (실제 실행)

```bash
cd ~/services/my-rag/compose
docker compose stop rag-db
curl -fsS http://127.0.0.1:8090/health
time curl -i -X POST http://127.0.0.1:8090/api/answers \
  -H 'Content-Type: application/json' \
  -d '{"question":"환불은 며칠 정도 걸리나요?"}'
# 복구
docker compose start rag-db
sleep 5
curl -fsS http://127.0.0.1:8090/health
```

**1차 실행 결과 (2026-09-10) — 실제 버그 2건 발견:**
1. `/health`가 DB 장애 시 커스텀 DOWN 응답이 아니라 **Spring Boot 기본 에러 페이지**(`{"timestamp":...,"error":"Internal Server Error","path":"/health"}`)를 반환함 — `curl -fsS`(`-f`)가 본문을 숨겨서 처음엔 못 알아챘고, `-i`로 재확인해서 발견. 원인: `HealthController.health()`가 `healthMapper.selectOne()`을 try-catch 없이 호출 — 기존 테스트(`health_DB_접근_실패시_예외를_삼키지_않고_그대로_전파한다`)가 이걸 의도된 동작으로 명시하고 있었음.
2. `/api/answers`가 DB 장애 시 sanitized 500을 반환하기까지 **30초** 소요 — `spring.datasource.hikari.connection-timeout` 미설정으로 HikariCP 기본값(30000ms)을 그대로 사용 중이었음. Embedding/LLM은 2초로 짧게 설정돼 있는데 DB만 누락돼 있었음.

**수정 내용:**
- `HealthController`: `DataAccessException`을 잡아서 `503 Service Unavailable` + `{"status":"DOWN","database":"DOWN"}` 반환하도록 변경. `deploy.sh`의 `curl -f` 헬스체크가 실패를 제대로 감지하려면 2xx가 아닌 상태 코드가 필요해서 503으로 결정.
- `HealthControllerTest`: 기존 "예외 전파" 테스트를 "503+DOWN 반환" 테스트로 교체.
- `application.yml`: `spring.datasource.hikari.connection-timeout: 5000` 추가(30초 → 5초).
- 로컬 `./gradlew clean test --no-daemon` 전체 통과 확인 후 Spark에 재배포, 재검증 완료:
  ```
  {"database":"DOWN","status":"DOWN"}   # /health, 503
  {"message":"답변 생성에 실패했습니다."}  # /api/answers, HTTP 500, 5.291s(기존 30초 대비 개선)
  ```

실행 결과: **PASS** (2026-09-10, 수정·재배포·재검증까지 완료) — 검색·정상 답변·근거 부족 응답(의도된 동작으로
재해석)·입력 검증·PostgreSQL 중단 복구 전부 확인. Embedding/LLM 중단 테스트는 공유 서비스 영향으로
실행 안 함(코드 기준 예상 동작만 기록, §위 참고).

---

## Task 11 — 운영 최소 점검

```bash
docker compose -f ~/services/my-rag/compose/docker-compose.yml ps
docker compose -f ~/services/my-rag/compose/docker-compose.app.yml ps
sudo ss -lntp
free -h
df -h
nvidia-smi
pg_dump --version
```

- [x] PostgreSQL(`rag-postgres`) healthy, Spring Boot(`my-rag-app`) Up
- [x] Embedding/LLM active — `sglang::scheduler`(87GB GPU 메모리), bge-m3-embedding 정상 확인
- [x] 5433/8003/8002 확인 — DB(5433)는 `127.0.0.1`만. 8003/8002는 my-rag 전용 아니라서 Tailscale
      인터페이스에도 열려 있는 상태(§포트 현황·향후 보안 개선 항목 참고, 새로 발견한 문제 아님)
- [x] **신규 발견**: `my-rag-app`(8090) 자체가 `network_mode: host` + Spring Boot 기본 바인딩(0.0.0.0)
      때문에 `*:8090`으로 모든 인터페이스에 열려 있음 — `ss -lntp`로 확인. Tailnet의 다른 기기가
      인증 없이 API 호출 가능. 사용자 판단: **나중에 실제 서비스화할 때 HTTPS 인증서·도메인
      라우팅·API 인증을 한 번에 같이 처리하기로 결정** — 지금은 손대지 않음(§향후 보안 개선 항목에 추가)
- [x] 디스크·GPU 메모리 여유 확인 — 디스크 3.2T 여유, GPU는 기존 sglang 87GB 사용 중이나 정상 범위
- [x] 애플리케이션 로그 위치·보존 기준 확인 — `docker logs my-rag-app`뿐(컨테이너 재생성 시 사라짐,
      영구 보존 필요하면 로그 드라이버/볼륨 마운트 추가 검토 — 향후 개선 항목)
- [x] `pg_dump` 확인 — 호스트에는 없지만 `rag-postgres` 컨테이너 안에 포함(`docker exec rag-postgres
      pg_dump --version` → PostgreSQL 17.11). 실제 백업 정책(저장 위치·보존 기간)은 미정, 지금 실행 안 함

실제 백업은 저장 위치·보존 기간·민감 데이터 처리 방식을 정한 뒤 실행한다. 운영 DB에서 무계획 dump 생성 금지. `docker compose down -v`는 기본 절차에 포함하지 않는다(데이터 삭제 명령).

실행 결과: **PASS** (2026-09-10)

---

## 실패 시 확인할 것

- 서비스 기동 실패: `docker logs my-rag-app --tail 200` (Docker 전환 후 — systemd `journalctl`은 더 이상 안 씀)
- DB 연결 실패: `docker compose ps`, `docker exec rag-postgres pg_isready -U rag -d rag_db`
- 포트 충돌: `sudo ss -lntp`
- Embedding/LLM 연결 실패: 각 프로세스 로그, `curl -v` 재현
- CD(`cd.yml`) 실패: Actions 탭에서 어느 스텝인지 확인 — Tailscale 연결/SSH/deploy.sh/헬스체크 중 어디서 끊겼는지가 원인 파악의 핵심

## 완료 기준

원본 계획 `.hermes/plans/2026-09-08_161535-dgx-spark-mvp-deployment.md` §5를 그대로 따른다.

---

## 향후 보안 개선 항목 (참고용 — 체크리스트 아님, 필요할 때 검토)

지금 당장 막는 문제는 아니지만, MVP 이후 실제 운영 단계로 갈 때 다시 볼 만한 항목들이다.

**컨테이너/런타임**
- `my-rag-app` 컨테이너가 root로 실행됨 (`Dockerfile`에 `USER` 지정 없음). 크래시 테스트할 때 `sudo kill`이 필요했던 이유이기도 함 — non-root 유저로 실행하도록 Dockerfile에 `USER` 추가 검토.
- CI에 이미지 취약점 스캔(Trivy 등) 미적용. 베이스 이미지(`eclipse-temurin`) CVE를 정기적으로 확인할 방법이 없음.

**네트워크**
- Embedding(8003)·LLM(8002) 서버가 `127.0.0.1`뿐 아니라 Tailscale 인터페이스(`100.90.113.121`)에도 바인딩되어 있어, 같은 Tailnet의 다른 기기가 Spring Boot API를 거치지 않고 직접 접근 가능(Task 5/6에서 발견, 계획서의 루프백 전용 원칙보다 넓음). 이 서버들이 my-rag 전용이 아니라 다른 프로젝트도 같이 쓰는 것 같아, 좁히기 전에 영향 범위 확인 필요.
- **확인됨(Task 11, 2026-09-10)**: `my-rag-app`(8090)이 `network_mode: host`+Spring Boot 기본 바인딩(0.0.0.0) 때문에 `*:8090`으로 모든 인터페이스에 열려 있음(`ss -lntp`로 확인) — Tailnet의 다른 기기가 인증 없이 API 호출 가능. Spring Boot API(8090) 평문 HTTP, TLS도 미적용. **사용자 결정: 나중에 실제 서비스화할 때 HTTPS 인증서·도메인 라우팅·API 인증을 한 번에 같이 처리하기로 함** — 지금 MVP 단계에서는 손대지 않음.
- API 자체에 인증·인가 없음(계획서에서 의도적으로 MVP 범위 제외) — 위 항목과 같이 처리 예정.

**접근 제어·키 관리**
- `SPARK_SSH_KEY`가 일반 계정 전체 권한(사실상 sudo 포함)을 가짐. Spark의 `authorized_keys`에 `command="~/services/my-rag/deploy.sh"` 같은 forced-command를 걸면 이 키로는 배포 스크립트 실행만 가능하게 제한 가능 — 지금은 안 되어 있음.
- Tailscale ACL로 GitHub Actions 임시 노드(`tag:ci`)를 Spark의 10022 포트로만 제한하는 것 — unmeto 계정 쪽에서 준비는 됐지만 적용은 보류함("일단 킵").
- **Ephemeral 키가 실제로 잘 동작하는지(연결 종료 후 노드 자동 삭제) 미확인** — unmeto 계정 접근이 지금 어려워서 나중에 확인. 확인 방법: unmeto 계정으로 https://login.tailscale.com/admin/machines 접속 → CD(`cd.yml`) 워크플로 실행 직후 `github-runnervm...` 이름의 기기가 목록에 보이는지 확인 → 완료 후 수십 초~분 내로 자동 삭제되는지 확인. 또는 Spark에서 `tailscale status`로 같은 걸 확인 가능.
- GHCR 이미지가 public — secret은 없지만 코드 구조가 외부에 노출됨. 필요시 private 전환 검토(단, Spark에서 pull할 때 인증 추가 필요해짐).

**운영**
- DB 정기 백업 미구현 (Task 11은 `pg_dump --version` 확인까지만, 실제 백업 정책·주기·보관은 미정).
- 애플리케이션 로그에 민감정보(credential, 내부 URL 등) 노출 안 되는지 정식 점검 안 됨.
- Rate limiting/DoS 방어 없음 (외부 미공개 상태라 지금은 낮은 우선순위).

---

## 향후 CI/CD·DevOps 개선 항목 (참고용 — 체크리스트 아님, 필요할 때 검토)

지금 규모(개인 프로젝트, Spark 한 대)에서는 급한 게 없지만, 서비스가 커지거나 사용자가 늘면 순서대로 볼 만한 항목들이다.

**배포 안정성**
- `deploy.sh`의 롤백이 `latest`/`previous` 딱 두 슬롯뿐 — 커밋 SHA 기준으로 이미지를 버전 관리하고 "이 SHA로 롤백"하는 방식이 더 견고함(2단계 이전으로는 지금 구조로 못 돌아감).
- 무중단 배포 아님 — 재배포 시 몇 초 다운타임 발생. 필요해지면 blue-green이나 "새 컨테이너 healthy 확인 후 기존 종료" 방식 검토.
- `cd.yml`에 concurrency 제어 없음(`ci.yml`엔 있음) — 짧은 간격으로 여러 커밋이 푸시되면 배포가 겹쳐 돌 수 있음.

**관측성(Observability)**
- 모니터링/알림 체계 없음 — 서비스가 죽어도 능동적으로 알려주는 게 없음(Slack/Discord webhook, uptime 체크 서비스 등). 지금은 사람이 수동 확인해야 함.
- 로그가 휘발성 — `docker logs`뿐이라 컨테이너 재생성되면 사라짐. 중앙 로그 수집(Loki 등)이나 최소한 파일 볼륨 마운트 필요.
- 배포 실패 시 알림 없음 — `deploy.sh` 실패는 GitHub Actions 페이지에서만 보임.

**보안/권한 (CI/CD 관점)**
- Tailscale ACL로 CI 임시 노드(`tag:ci`) 범위를 Spark 하나로 좁히는 것 — unmeto 계정 쪽에서 준비는 됐지만 적용 보류 중(§향후 보안 개선 항목과 동일 항목).
- Secret 순환(rotation) 정책 없음 — SSH 키·Tailscale authkey 등을 정기적으로 교체하는 프로세스 없음.
- Required reviewer가 본인 1명뿐 — 개인 프로젝트라 문제없지만 팀 규모 커지면 승인자 이중화 고려.

**테스트/검증**
- CI는 Mock 기반이라 실제 LLM 동작 변동성(Task 6/10에서 발견한 reasoning 모드로 인한 빈 응답 리스크 등)을 못 잡음 — 배포 후 정기적인 실제 smoke test 자동화 여지.
- `build` job이 `push to main`에만 걸려 있어 PR 단계에서는 Docker 이미지 빌드 자체가 되는지 미리 확인 안 됨.
