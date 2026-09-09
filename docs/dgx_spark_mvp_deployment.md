# DGX Spark MVP 배포 문서

> 상태(2026-09-09): Task 3(Postgres, 5433)·4(하드웨어, Java 17)·5(Embedding, 8003)·6(LLM, 8002) PASS.
> 배포 방식을 JAR+systemd에서 **Docker 컨테이너(`network_mode: host`)**로 전환(Task 7 아키텍처 변경 참고) —
> JAR 방식으로는 이미 Task 7 PASS까지 확인했었고, 로컬 Docker 이미지도 검증 완료. Spark에서 실제
> 컨테이너로 띄우는 것과 Task 8(재시작 정책) 이후는 아직 `[ ]`/`UNVERIFIED`다.
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

- [ ] `docker compose ps`에서 rag-postgres healthy
- [ ] `pg_isready` → accepting connections
- [ ] vector extension 존재
- [ ] 5432가 아닌 5433에 바인딩됨 (`docker ps` 포트 컬럼 확인)

실행 결과: UNVERIFIED

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

**4. 운영 환경변수 파일** (Spark, `~/services/my-rag/config/my-rag.env` — 이미 Task 7 JAR 검증 때 만든 것 그대로 재사용 가능)
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
- [ ] Spark에서 Docker Compose로 실제 기동 — **UNVERIFIED**, 아래 절차 실행 필요
- [ ] OpenAPI JSON / Swagger UI 200 (Spark 컨테이너 기준) — UNVERIFIED

실행 결과: **부분 PASS** — 로직 자체(JAR 실행, 로컬 Docker 이미지)는 검증됐고, Spark에서 실제 컨테이너로 띄우는 것만 남음

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

- [ ] `RestartPolicy.Name`이 `unless-stopped`
- [ ] `docker` 서비스가 `enabled` (재부팅 시 자동 기동)
- [ ] 컨테이너를 강제로 죽여도(`docker kill my-rag-app`) 자동 재시작되는지 확인

실행 결과: UNVERIFIED

---

## Task 9 — MVP E2E 검증 데이터 준비

최소 테스트 문서 3개 (주문 취소 정책 / 환불 정책 / 배송 정책). 개인정보·Secret·실제 내부 URL을 넣지 않는다. `document.source-directory` 설정과 실제 색인 대상 디렉터리를 일치시킨다.

```bash
curl -fsS -X POST http://127.0.0.1:8090/api/documents/index
docker exec rag-postgres psql -U rag -d rag_db -c 'SELECT COUNT(*) FROM tb_document;'
docker exec rag-postgres psql -U rag -d rag_db -c 'SELECT COUNT(*) FROM tb_document_chunk;'
```

- [ ] HTTP 200
- [ ] `documentCount`가 테스트 문서 수와 일치
- [ ] `chunkCount` > 0
- [ ] `embeddingDimension` == 1024

실행 결과: UNVERIFIED

---

## Task 10 — 검색·답변 E2E 검증

**검색**
```bash
curl -fsS --get http://127.0.0.1:8090/api/search \
  --data-urlencode 'query=주문을 취소하려면 어떻게 해야 하나요?'
```
- [ ] HTTP 200, distance 오름차순, 관련 필드 존재

**답변**
```bash
curl -fsS -X POST http://127.0.0.1:8090/api/answers \
  -H 'Content-Type: application/json' \
  -d '{"question":"주문을 취소하려면 어떻게 해야 하나요?"}'
```
- [ ] HTTP 200, answer 비어있지 않음, sources가 Context와 일치, sources에 content 미포함

**근거 없는 질문**
```bash
curl -fsS -X POST http://127.0.0.1:8090/api/answers \
  -H 'Content-Type: application/json' \
  -d '{"question":"테스트 문서에 없는 완전히 무관한 질문"}'
```
- [ ] 검색 결과 없으면 sources=[], LLM 미호출

**입력 오류**
```bash
curl -i -X POST http://127.0.0.1:8090/api/answers \
  -H 'Content-Type: application/json' \
  -d '{"question":"   "}'
```
- [ ] HTTP 400, 내부 상세 미노출

**장애 시나리오** (운영 데이터 있는 환경에서는 실행하지 않음, 사전 승인 필요)
- Embedding API 중단 후 `/api/search`
- LLM API 중단 후 `/api/answers`
- PostgreSQL 중단 후 `/health`, API 호출

- [ ] timeout 후 무한 대기 없음
- [ ] HTTP 500 sanitized message
- [ ] credential·내부 상세 미노출
- [ ] 로그에서 원인 추적 가능

실행 결과: UNVERIFIED

---

## Task 11 — 운영 최소 점검

```bash
docker compose ps
systemctl is-active my-rag
ss -lntp
free -h
df -h
nvidia-smi
pg_dump --version
```

- [ ] PostgreSQL healthy, Spring Boot active
- [ ] Embedding/LLM active 또는 외부 endpoint 접근 가능
- [ ] 5433/8003/8002 외부 미공개
- [ ] 디스크·GPU 메모리 여유 확인
- [ ] 애플리케이션 로그 위치·보존 기준 확인

실제 백업은 저장 위치·보존 기간·민감 데이터 처리 방식을 정한 뒤 실행한다. 운영 DB에서 무계획 dump 생성 금지. `docker compose down -v`는 기본 절차에 포함하지 않는다(데이터 삭제 명령).

실행 결과: UNVERIFIED

---

## 실패 시 확인할 것

- 서비스 기동 실패: `journalctl -u my-rag -n 200 --no-pager`
- DB 연결 실패: `docker compose ps`, `docker exec rag-postgres pg_isready -U rag -d rag_db`
- 포트 충돌: `ss -lntp`
- Embedding/LLM 연결 실패: 각 프로세스 로그, `curl -v` 재현

## 완료 기준

원본 계획 `.hermes/plans/2026-09-08_161535-dgx-spark-mvp-deployment.md` §5를 그대로 따른다.
