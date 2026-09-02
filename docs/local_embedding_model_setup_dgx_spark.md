# DGX Spark 로컬 Embedding 모델 설치·운영 가이드

이 문서는 DGX Spark 한 대에 RAG Backend, PostgreSQL + pgvector, Embedding 모델을 함께 설치·운영하는 구성을 정리한 실행 가이드다.

처음에는 `BAAI/bge-m3` 하나만 사용한다. 문서와 질문을 모두 같은 모델로 변환하고, 결과 벡터를 PostgreSQL + pgvector에 저장하는 것을 목표로 한다.

## 1. 변경된 전체 구성

이번 구성에서는 EC2나 NAS를 별도 애플리케이션 서버로 사용하지 않는다. DGX Spark가 다음 역할을 모두 맡는다.

```text
[DGX Spark 한 대]

Spring Boot RAG API
  ├─ 문서 수집·Chunking
  ├─ Embedding API 호출
  ├─ PostgreSQL + pgvector 검색
  └─ LLM 호출 및 답변 반환
       │
       ├─ PostgreSQL + pgvector
       └─ BGE-M3 Embedding 서버
```

구성 요소는 같은 장비에 두지만, 프로세스와 포트는 분리한다.

```text
Spring Boot RAG API       :8080
PostgreSQL + pgvector     :5432 (외부 공개 금지)
BGE-M3 Embedding API      :8000 (외부 공개 금지)
```

여기서 “같은 포트”로 여러 서버를 실행할 수는 없다. 같은 DGX Spark의 같은 호스트에 배치하는 것은 가능하지만, 각 프로세스는 서로 다른 포트를 사용해야 한다. 외부 사용자에게는 Spring Boot의 `:8080`만 공개하고, PostgreSQL과 Embedding API는 `127.0.0.1` 또는 내부 네트워크에서만 접근하게 하는 구성이 기본이다.

```text
사용자
  ↓ :8080
Spring Boot RAG API
  ├─→ 127.0.0.1:5432 PostgreSQL
  └─→ 127.0.0.1:8000 BGE-M3
```

이 구조는 1단계 MVP에 적합하다. 네트워크 호출이 모두 로컬 루프백에서 일어나므로 구조가 단순하고, 외부 API 노출도 줄어든다. 다만 Spring Boot, PostgreSQL, Embedding 서버가 같은 장비의 CPU·메모리·디스크·네트워크 자원을 공유하므로 운영 시 자원 모니터링이 필요하다.

## 2. 모델 선택

### 2.1 1순위: BAAI/bge-m3

초기 프로젝트에는 `BAAI/bge-m3`를 추천한다.

- 한국어와 영어를 포함한 다국어 문서에 적합
- 100개 이상의 언어 지원
- 최대 8,192 토큰 입력 지원
- Dense Embedding 기준 1,024차원 벡터 생성
- 문서 Chunk와 사용자 질문을 같은 모델로 변환 가능
- 나중에 Sparse Retrieval, Hybrid Search로 확장할 여지가 있음
- vLLM이 BGE-M3 Embedding 모델을 지원하고 OpenAI 호환 Embeddings API를 제공함

초기에는 BGE-M3의 Dense Embedding만 사용한다. Sparse 벡터와 ColBERT 방식까지 한 번에 도입하지 않는다.

### 2.2 Vector DB 설정

BGE-M3의 Dense Embedding 차원은 1,024이므로 PostgreSQL 컬럼은 다음처럼 설정한다.

```sql
embedding vector(1024)
```

이전에 검토한 OpenAI `text-embedding-3-small`의 1,536차원 설정과 섞으면 안 된다. Embedding 모델을 바꾸면 벡터 차원이 달라질 수 있고, 기존 벡터와의 비교 기준도 달라지므로 문서 전체를 새 모델로 다시 색인해야 한다.

## 3. DGX Spark 사전 확인

DGX Spark에서 다음을 먼저 확인한다.

```bash
uname -m
nvidia-smi
python3 --version
docker --version
```

기대하는 결과는 다음과 같다.

```text
uname -m       → aarch64
nvidia-smi     → NVIDIA GPU와 드라이버 정보
python3        → Python 3.x
Docker         → 설치된 Docker 버전
```

DGX Spark는 ARM 기반 시스템이므로 일반 x86 서버용 설치 명령이나 이미지를 그대로 사용하지 말고, ARM64와 DGX Spark의 현재 소프트웨어 버전에 맞는지 확인한다.

먼저 NVIDIA 드라이버를 별도로 설치하거나 교체하지 않는다. DGX Spark 기본 이미지와 DGX OS의 CUDA 환경을 우선 사용한다.

## 4. 운영 방식 선택

### 4.1 권장: vLLM API 서버

EC2와 NAS에서 호출할 서버가 필요하므로 vLLM을 권장한다.

```text
BGE-M3 모델
  ↓
vLLM
  ↓
OpenAI 호환 POST /v1/embeddings
```

장점:

- HTTP API로 분리 가능
- 여러 입력을 한 번에 처리하는 배치 요청 가능
- OpenAI Embeddings API와 비슷한 요청 형식
- Spring Boot에서 일반적인 REST Client로 호출 가능
- 나중에 다른 Embedding 모델로 교체하기 쉬움

### 4.2 대안: Python 프로세스 직접 실행

모델을 Python 코드에서 직접 로드해 테스트할 수도 있다.

```python
from sentence_transformers import SentenceTransformer

model = SentenceTransformer("BAAI/bge-m3", device="cuda")
embeddings = model.encode(
    ["결제 완료 후 주문을 취소하려면?"],
    normalize_embeddings=True,
)
print(len(embeddings[0]))
```

이 방식은 단일 머신에서 빠르게 검증할 때는 좋지만, EC2와 NAS에서 호출할 API 서버가 필요하다면 vLLM 방식이 더 적합하다.

## 5. vLLM 설치

DGX Spark의 NVIDIA 공식 문서와 vLLM의 현재 설치 안내를 먼저 확인한다. DGX Spark의 ARM64 환경에서는 일반적인 x86 CUDA Wheel이 동작하지 않을 수 있으므로, 다음 두 방식 중 실제 환경에서 설치되는 방식을 선택한다.

### 방식 A: DGX Spark에 vLLM이 이미 제공되는 경우

```bash
python3 -m pip show vllm
```

설치되어 있다면 버전을 확인한다.

```bash
python3 -c "import vllm; print(vllm.__version__)"
```

### 방식 B: Docker 또는 NVIDIA 제공 컨테이너 사용

DGX Spark는 NVIDIA Container Runtime을 사용할 수 있다. 먼저 GPU 컨테이너가 동작하는지 확인한다.

```bash
docker run --rm --runtime=nvidia \
  nvcr.io/nvidia/pytorch:25.02-py3 \
  python3 -c "import torch; print(torch.cuda.is_available())"
```

이미지 태그는 DGX Spark의 현재 DGX OS·CUDA 버전에 맞게 조정한다. 위 명령이 이미지 미존재나 아키텍처 불일치로 실패하면 임의의 태그를 계속 바꾸기보다, DGX Spark Release Notes와 NGC의 ARM64 지원 이미지를 확인한다.

### 방식 C: Python 가상환경에 설치

직접 설치를 시도할 때는 가상환경을 만든다.

```bash
python3 -m venv ~/venvs/bge-m3
source ~/venvs/bge-m3/bin/activate
python -m pip install --upgrade pip
python -m pip install vllm
```

설치가 실패하면 실패 원인을 먼저 확인한다.

```bash
python -m pip show vllm
python -c "import torch; print(torch.__version__); print(torch.cuda.is_available())"
```

DGX Spark에서 vLLM Wheel이 아직 현재 CUDA·ARM64 조합을 지원하지 않는 경우에는 Sentence Transformers 또는 FlagEmbedding 직접 실행 방식으로 먼저 검증하고, API 서버는 FastAPI 등으로 얇게 감싼다. 설치가 되지 않은 상태에서 실행 명령만 반복하지 않는다.

## 6. 모델 다운로드

Hugging Face에서 모델을 처음 실행할 때 모델 파일을 다운로드한다.

```bash
huggingface-cli download BAAI/bge-m3 \
  --local-dir ~/models/bge-m3
```

`huggingface-cli`가 없다면 Hugging Face Hub 패키지를 설치한다.

```bash
python3 -m pip install -U huggingface_hub
```

그다음 다음처럼 다운로드한다.

```bash
python3 -m huggingface_hub.commands.huggingface_cli download \
  BAAI/bge-m3 \
  --local-dir ~/models/bge-m3
```

환경에 따라 CLI 모듈 경로가 달라질 수 있다. 아래 명령으로 실제 설치된 CLI를 확인한다.

```bash
which huggingface-cli
huggingface-cli --help
```

모델을 다운로드한 뒤 파일이 있는지 확인한다.

```bash
find ~/models/bge-m3 -maxdepth 2 -type f | head
```

`find`는 파일 확인용 명령이고, 프로젝트의 문서 검색·편집에는 Obsidian 파일 도구를 사용한다.

## 7. vLLM으로 Embedding API 실행

먼저 포그라운드에서 동작을 확인한다.

```bash
vllm serve BAAI/bge-m3 \
  --task embed \
  --host 127.0.0.1 \
  --port 8000
```

로컬에 다운로드한 모델을 사용할 경우:

```bash
vllm serve ~/models/bge-m3 \
  --task embed \
  --host 127.0.0.1 \
  --port 8000
```

vLLM 버전에 따라 BGE-M3의 모델 아키텍처를 명시해야 할 수 있다.

```bash
vllm serve BAAI/bge-m3 \
  --task embed \
  --hf-overrides '{"architectures":["BgeM3EmbeddingModel"]}' \
  --host 127.0.0.1 \
  --port 8000
```

위 옵션은 버전에 따라 필요하지 않을 수 있다. 먼저 기본 명령을 실행하고, 모델 아키텍처 인식 오류가 발생할 때만 추가한다.

모델이 로드될 때까지 터미널 로그를 확인한다. 정상 실행 여부는 서버가 포트 8000에서 대기하는지와 API 요청이 실제 벡터를 반환하는지로 판단한다.

## 8. API 호출 테스트

DGX Spark 안에서 먼저 호출한다.

```bash
curl http://127.0.0.1:8000/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{
    "model": "BAAI/bge-m3",
    "input": "결제 완료 후 주문을 취소하려면?"
  }'
```

응답에서 확인할 항목:

- HTTP 200
- `data[0].embedding` 배열 존재
- 벡터 길이 1,024
- `model` 값 확인
- 오류 없이 반복 호출 가능

JSON 전체가 너무 길면 Python으로 벡터 길이만 확인한다.

```bash
curl -s http://127.0.0.1:8000/v1/embeddings \
  -H "Content-Type: application/json" \
  -d '{
    "model": "BAAI/bge-m3",
    "input": "결제 완료 후 주문을 취소하려면?"
  }' \
  | python3 -c 'import json,sys; x=json.load(sys.stdin); print(len(x["data"][0]["embedding"]))'
```

기대값:

```text
1024
```

## 9. 같은 DGX Spark에서 구성 요소 연결하기

이번 구성에서는 EC2·NAS에서 Embedding 서버를 원격 호출하지 않는다. Spring Boot, PostgreSQL, BGE-M3 Embedding 서버가 모두 같은 DGX Spark에서 실행되므로 다음처럼 루프백 주소로 연결한다.

```text
Spring Boot :8080
  ├─→ PostgreSQL :5432
  └─→ BGE-M3    :8000
```

각 프로세스가 사용하는 포트는 달라야 한다. 하나의 IP와 여러 포트를 사용하는 것은 자연스럽지만, 여러 프로세스가 같은 포트를 동시에 사용할 수는 없다.

권장 바인딩:

```text
Spring Boot       0.0.0.0:8080 또는 reverse proxy 뒤
PostgreSQL        127.0.0.1:5432
BGE-M3 Embedding  127.0.0.1:8000
```

Spring Boot 설정 예시:

```yaml
embedding:
  base-url: http://127.0.0.1:8000
  model: BAAI/bge-m3
  dimension: 1024
  connect-timeout: 2s
  read-timeout: 30s

spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/rag
```

사용자는 Spring Boot API만 호출한다.

```text
사용자 → DGX Spark:8080 → PostgreSQL:5432
                         → BGE-M3:8000
```

PostgreSQL과 Embedding API 포트는 인터넷에 공개하지 않는다. 외부 공개가 필요한 것은 일반적으로 Spring Boot의 `:8080` 하나뿐이며, 실제 운영에서는 Nginx·Caddy 같은 Reverse Proxy를 앞에 두고 HTTPS를 적용하는 방식을 검토한다.

다른 장비에서 관리자용으로 접근해야 한다면 VPN, SSH 터널, 방화벽 allowlist 중 하나를 사용한다. 내부 프로세스 간 통신에는 별도 VPN이 필요하지 않다.

## 10. Spring Boot 연동 개념

Spring Boot는 vLLM의 OpenAI 호환 API를 일반 HTTP API처럼 호출한다.

```text
POST {embedding.base-url}/v1/embeddings
Content-Type: application/json

{
  "model": "BAAI/bge-m3",
  "input": "결제 완료 후 주문을 취소하려면?"
}
```

환경 설정 예시:

```yaml
embedding:
  base-url: http://127.0.0.1:8000
  model: BAAI/bge-m3
  dimension: 1024
  connect-timeout: 2s
  read-timeout: 30s
```

구성 책임은 다음처럼 나눈다.

```text
EmbeddingClient
  └─ vLLM API 호출

DocumentIndexer
  └─ Chunk별 Embedding 생성 후 PostgreSQL 저장

Retriever
  └─ 질문 Embedding 생성 후 pgvector 검색
```

문서와 질문 모두 `EmbeddingClient`를 사용해야 하며, 모델명·차원·정규화 여부를 한 곳에서 관리한다.

## 11. pgvector 연동 주의점

BGE-M3를 사용하면 테이블의 벡터 차원은 1,024이다.

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_chunk (
    id BIGSERIAL PRIMARY KEY,
    document_id VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    source VARCHAR(1000) NOT NULL,
    embedding vector(1024) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

Cosine distance 검색 예시:

```sql
SELECT id, document_id, content, source,
       embedding <=> CAST(:query_embedding AS vector) AS distance
FROM document_chunk
ORDER BY embedding <=> CAST(:query_embedding AS vector)
LIMIT 5;
```

실제 JDBC 바인딩 방식은 사용하는 드라이버와 라이브러리에 따라 다를 수 있다. 처음에는 Embedding API 응답이 1,024개 숫자를 반환하는지 확인한 뒤 저장 로직을 연결한다.

Embedding 모델을 `text-embedding-3-small`로 바꾸면 기존 `vector(1024)` 테이블과 호환되지 않는다. 모델 교체 시에는 다음을 함께 관리해야 한다.

- 모델명
- 벡터 차원
- distance metric
- 문서 색인 시각
- embedding model version

## 12. 운영용 systemd 예시

DGX Spark에서 Docker가 아닌 Python 가상환경으로 vLLM을 실행하고, 재부팅 후에도 자동으로 띄우려면 systemd 서비스를 사용할 수 있다. 실제 사용자명과 가상환경 경로를 바꿔야 한다.

```ini
[Unit]
Description=BGE-M3 Embedding API
After=network-online.target
Wants=network-online.target

[Service]
User=YOUR_USER
WorkingDirectory=/home/YOUR_USER
Environment=PATH=/home/YOUR_USER/venvs/bge-m3/bin:/usr/local/bin:/usr/bin
ExecStart=/home/YOUR_USER/venvs/bge-m3/bin/vllm serve /home/YOUR_USER/models/bge-m3 --task embed --host 127.0.0.1 --port 8000
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

저장 후 적용:

```bash
sudo systemctl daemon-reload
sudo systemctl enable bge-m3
sudo systemctl start bge-m3
sudo systemctl status bge-m3
journalctl -u bge-m3 -f
```

VPN 또는 Reverse Proxy를 통해 외부의 EC2·NAS가 접근하는 경우에만 `127.0.0.1` 바인딩 대신 사설 인터페이스 바인딩을 검토한다. `0.0.0.0`은 방화벽과 인증을 함께 설정한 경우에만 사용한다.

## 13. Docker 운영 예시

환경이 확인된 뒤에는 Docker로 고정할 수 있다.

```bash
docker run --rm --gpus all \
  --name bge-m3 \
  -p 8000:8000 \
  -v $HOME/models:/models \
  vllm/vllm-openai:latest \
  /models/bge-m3 \
  --task embed \
  --host 0.0.0.0 \
  --port 8000
```

단, `latest` 이미지는 DGX Spark의 ARM64·CUDA 환경과 맞지 않을 수 있다. 운영 전에는 다음을 고정한다.

- 컨테이너 이미지 태그
- CUDA 버전
- vLLM 버전
- 모델 revision
- DGX OS 버전

Docker 이미지가 실행되지 않으면 Python 설치 방식으로 돌아가서 원인을 분리한다. 모델 문제인지, vLLM 이미지의 아키텍처 문제인지, CUDA 런타임 문제인지 한 번에 섞어 진단하지 않는다.

## 14. 검증 체크리스트

### 모델 실행

- [ ] DGX Spark에서 `nvidia-smi` 실행
- [ ] 모델 파일 다운로드 완료
- [ ] vLLM 프로세스 기동
- [ ] `/v1/embeddings` HTTP 200
- [ ] 벡터 길이 1,024 확인
- [ ] 한국어 문장 반복 호출 성공

### 검색 품질

- [ ] 문서 Chunk 3~5개 색인
- [ ] 질문 벡터 생성
- [ ] 관련 Chunk가 Top-K 안에 포함
- [ ] 무관한 질문에 관련 없는 Chunk만 반환되지 않는지 확인
- [ ] 동일한 질문에 결과가 안정적으로 나오는지 확인

### 연결

- [ ] EC2 또는 NAS에서 DGX Spark 사설 IP 접근
- [ ] VPN·방화벽 규칙 확인
- [ ] 인증 없는 외부 접근 차단
- [ ] 연결 Timeout 설정
- [ ] DGX Spark 중단 시 애플리케이션 오류 처리

### 데이터 일관성

- [ ] 문서와 질문에 동일한 Embedding 모델 사용
- [ ] PostgreSQL `vector(1024)` 확인
- [ ] cosine distance 기준 확인
- [ ] 모델 변경 시 전체 재색인 절차 정의

## 15. 초기 개발 권장 순서

```text
1. DGX Spark에서 BGE-M3를 Python으로 한 번 실행
2. 벡터 길이 1,024 확인
3. vLLM API 서버로 전환
4. curl로 /v1/embeddings 검증
5. EC2 또는 NAS에서 원격 호출
6. PostgreSQL + pgvector에 Chunk와 벡터 저장
7. 질문 벡터로 Top-K 검색
8. 검색 결과를 LLM Context로 전달
9. 모델·차원·거리 기준을 설정 파일로 고정
```

처음부터 systemd, Docker, VPN, 모니터링을 모두 구성하지 않는다. 로컬 실행과 API 호출을 확인한 뒤 네트워크 연결, 자동 재시작, 컨테이너 고정 순서로 운영 요소를 추가한다.

## 참고 자료

- [BAAI/bge-m3 - Hugging Face](https://huggingface.co/BAAI/bge-m3) — 모델 사양, 다국어 지원, 1,024차원, 8,192 토큰
- [vLLM Embedding Usages](https://docs.vllm.ai/en/latest/models/pooling_models/embed/) — Embedding 실행 방식, BGE-M3 지원, OpenAI 호환 API
- [NVIDIA DGX Spark User Guide](https://docs.nvidia.com/dgx/dgx-spark/) — DGX Spark 설치·운영·소프트웨어 문서
- [NVIDIA DGX Spark Hardware Overview](https://docs.nvidia.com/dgx/dgx-spark/hardware.html) — ARM CPU, GPU, 128GB 통합 메모리 등 하드웨어 정보
- [pgvector](https://github.com/pgvector/pgvector) — PostgreSQL 벡터 타입과 유사도 검색

## 주의사항

- 이 문서의 설치 명령은 DGX Spark의 현재 DGX OS, CUDA, vLLM 버전에 따라 조정될 수 있다.
- 특히 ARM64 환경에서 `pip install vllm`이나 임의의 Docker 이미지를 바로 성공한다고 가정하지 않는다.
- 실행 전에 GPU·CUDA·Python·Docker·vLLM 버전을 확인하고, 실패 시 Python 직접 실행 방식으로 문제 범위를 좁힌다.
- BGE-M3는 Embedding 모델이지 답변을 생성하는 LLM이 아니다. 최종 답변 생성에는 별도의 LLM이 필요하다.
- 외부 요청을 받는 API로 운영할 때는 반드시 사설 네트워크, 인증, 방화벽, Timeout을 함께 적용한다.
- 운영 환경에서는 모델과 라이브러리 버전을 고정하고, 변경 시 검색 평가를 다시 실행한다.

## 변경 표시

이 문서는 DGX Spark에서 BGE-M3를 로컬 Embedding 서버로 운영하기 위한 새 실행 문서다. 실제 DGX Spark에서 명령을 실행한 결과가 아니므로, 설치·기동 단계는 장비의 현재 환경에서 순서대로 검증해야 한다.
