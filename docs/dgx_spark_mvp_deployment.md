# DGX Spark MVP 배포 문서

> 상태: 저장소 쪽 준비만 완료. 아래 Spark 실기기 절차는 아직 실행 전이라 전부 `[ ]`/`UNVERIFIED`다.
> 원본 계획: `.hermes/plans/2026-09-08_161535-dgx-spark-mvp-deployment.md`
> 실제 Spark 사용자명·IP·경로·Secret은 이 문서에 절대 기록하지 않는다. 아래 `<placeholder>`를 실행 시점에만 실제 값으로 치환한다.

## 네트워크 구성

```text
Spring Boot API (127.0.0.1:8080, 필요 시만 외부 노출)
  → PostgreSQL   127.0.0.1:5432 (외부 비공개)
  → Embedding    127.0.0.1:8000 (외부 비공개)
  → LLM          127.0.0.1:8002 (외부 비공개)
```

외부 관리 접근은 SSH tunnel / Tailscale / 방화벽 allowlist 중 하나로만 한다. `0.0.0.0` 바인딩은 방화벽·인증 정책 확인 전에는 사용하지 않는다.

## 배포 디렉터리 (예시)

```text
/opt/my-rag/app/             # JAR
/opt/my-rag/config/          # 운영 EnvironmentFile (권한 600)
/opt/my-rag/logs/
/opt/my-rag/data/markdown/    # 색인 대상 Markdown
/opt/my-rag/compose/          # docker-compose.yml
```

`/opt` 사용 권한이 없으면 `/home/<spark-user>/services/my-rag/`로 대체한다.

## Secret 원칙

- `DB_PASSWORD`, `LLM_API_KEY`는 Git에 기록하지 않는다.
- 운영 환경변수는 systemd `EnvironmentFile`(권한 600) 또는 배포 시 shell 환경변수로만 주입한다.
- `application.yml`, README, systemd unit, 로그에 실제 Secret을 남기지 않는다.

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

- [ ] `uname -m` 결과가 실제 아키텍처와 일치
- [ ] NVIDIA GPU/드라이버 표시됨
- [ ] Java 17 사용 가능
- [ ] Docker daemon 실행 중
- [ ] NVIDIA Container Runtime 사용 가능 여부 확인
- [ ] 모델·볼륨용 디스크 여유 확인
- [ ] 메모리/GPU 메모리 여유 기록

실행 결과: UNVERIFIED (Spark 미접속)

문제 발생 시 NVIDIA 드라이버를 임의 교체하지 않는다. DGX Spark 기본 이미지·DGX OS·CUDA 조합을 먼저 확인한다.

---

## Task 5 — BGE-M3 Embedding API 실행

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
  --port 8000
```

**검증**
```bash
curl -fsS http://127.0.0.1:8000/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model":"BAAI/bge-m3","input":"결제 완료 후 주문을 취소하려면?"}' \
  | python3 -c 'import json,sys; x=json.load(sys.stdin); print(len(x["data"][0]["embedding"]))'
```

- [ ] HTTP 200
- [ ] `data[0].embedding` 존재, 길이 1024
- [ ] 한국어 문장 반복 호출 성공
- [ ] 모델 로딩 오류 없음
- [ ] 8000 포트가 외부 전체 인터페이스에 노출되지 않음

실행 결과: UNVERIFIED — 실제 Embedding API 확인 전까지 문서 색인(Task 9)을 진행하지 않는다.

---

## Task 6 — LLM API 실행 또는 외부 연결 확인

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

- [ ] HTTP 200
- [ ] `choices[0].message.content` 존재
- [ ] 응답 시간 < read-timeout
- [ ] API Key 없는 서버는 Authorization 헤더 없이 호출

실행 결과: UNVERIFIED

---

## Task 7 — Spring Boot JAR 빌드 및 Spark 배포

**빌드 (CI 또는 로컬)**
```bash
./gradlew clean test bootJar --no-daemon
ls -lh build/libs/
```

**Spark로 전달**
```bash
scp build/libs/<jar-name>.jar <spark-user>@<spark-host>:<deploy-directory>/app/my-rag.jar
```

**운영 환경변수 예시** (`deploy/dgx-spark/my-rag.service.example` 참고)
```text
DB_URL=jdbc:postgresql://127.0.0.1:5432/rag_db
DB_USERNAME=rag
DB_PASSWORD=<outside repository>
EMBEDDING_BASE_URL=http://127.0.0.1:8000/v1
EMBEDDING_MODEL=BAAI/bge-m3
EMBEDDING_DIMENSION=1024
LLM_BASE_URL=http://127.0.0.1:8002/v1
LLM_MODEL=<actual-model-id>
LLM_API_KEY=<optional, outside repository>
SERVER_PORT=8080
```

**최초 실행 (systemd 등록 전 포그라운드로 먼저 확인)**
```bash
set -a
source <protected-env-file>
set +a
java -jar <deploy-directory>/app/my-rag.jar
```

**검증**
```bash
curl -fsS http://127.0.0.1:8080/health
curl -fsS http://127.0.0.1:8080/v3/api-docs
curl -fsS -I http://127.0.0.1:8080/swagger-ui.html
```

- [ ] API 프로세스 기동
- [ ] Flyway migration 성공
- [ ] DB 연결 성공
- [ ] OpenAPI JSON 200
- [ ] Swagger UI 200/redirect
- [ ] `/health` 애플리케이션·DB 상태 정상

실행 결과: UNVERIFIED. `/health` 실패 원인을 무시하고 다음 단계로 진행하지 않는다.

---

## Task 8 — systemd 서비스 전환

템플릿: `deploy/dgx-spark/my-rag.service.example`, 배포 스크립트 예시: `deploy/dgx-spark/deploy.example.sh`

```bash
chmod 600 <protected-env-file>
sudo systemctl daemon-reload
sudo systemctl enable --now my-rag
sudo systemctl status my-rag
journalctl -u my-rag -n 100 --no-pager
```

`User`, `WorkingDirectory`, `EnvironmentFile`, JAR 경로는 실제 값으로 치환한다. 실제 Java 경로는 `command -v java`로 확인한다.

파괴적이거나 시스템 전체 설정을 바꾸는 명령은 사용자의 명시적 승인 없이 실행하지 않는다.

**검증**
```bash
systemctl is-active my-rag
curl -fsS http://127.0.0.1:8080/health
```

실행 결과: UNVERIFIED

---

## Task 9 — MVP E2E 검증 데이터 준비

최소 테스트 문서 3개 (주문 취소 정책 / 환불 정책 / 배송 정책). 개인정보·Secret·실제 내부 URL을 넣지 않는다. `document.source-directory` 설정과 실제 색인 대상 디렉터리를 일치시킨다.

```bash
curl -fsS -X POST http://127.0.0.1:8080/api/documents/index
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
curl -fsS --get http://127.0.0.1:8080/api/search \
  --data-urlencode 'query=주문을 취소하려면 어떻게 해야 하나요?'
```
- [ ] HTTP 200, distance 오름차순, 관련 필드 존재

**답변**
```bash
curl -fsS -X POST http://127.0.0.1:8080/api/answers \
  -H 'Content-Type: application/json' \
  -d '{"question":"주문을 취소하려면 어떻게 해야 하나요?"}'
```
- [ ] HTTP 200, answer 비어있지 않음, sources가 Context와 일치, sources에 content 미포함

**근거 없는 질문**
```bash
curl -fsS -X POST http://127.0.0.1:8080/api/answers \
  -H 'Content-Type: application/json' \
  -d '{"question":"테스트 문서에 없는 완전히 무관한 질문"}'
```
- [ ] 검색 결과 없으면 sources=[], LLM 미호출

**입력 오류**
```bash
curl -i -X POST http://127.0.0.1:8080/api/answers \
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
- [ ] 5432/8000/8002 외부 미공개
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
