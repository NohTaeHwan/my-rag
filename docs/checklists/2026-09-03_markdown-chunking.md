# 개발 작업별 claude-kit 준수 체크리스트

## 작업 정보

- 작업명: RAG MVP 4단계 — Markdown 문서·Chunking (`document` 패키지)
- 작업 일자: 2026-09-03
- 관련 문서: `.hermes/plans/2026-09-03_102020-markdown-chunking-stage4.md`, `docs/rag_mvp_development_plan.md`(4단계)
- 담당: nohtaehwan (Claude Code 협업)

## 1. 작업 시작 전

- [x] 요청 범위와 완료 기준을 정리했다. — DB 저장/Embedding 호출/API 없이 파싱·Chunking 도메인 로직까지만
- [x] 요구사항이 모호한 부분을 먼저 확인했다. — 길이 측정 단위, 입력 경로 정책, 파서 구현 수준 3가지를 실제 `docs/` 문서 분석 후 사용자와 함께 확정
- [x] `CLAUDE.md`를 읽었다.
- [x] 참고 문서 이정표에서 관련 문서를 읽었다. — 기획 문서(`.hermes/plans/...stage4.md`) 전체 분석
- [x] 변경 대상의 기존 구조와 사용처를 확인했다. — `document` 패키지 이전엔 없었음, 신규 생성
- [ ] 신규 API면 `spring-api-create` 등 Skill을 확인했다. — 해당 없음 (신규 REST 엔드포인트 없음)

## 2. 설계·구현

- [x] 기존 Controller → Service → 데이터 접근 구조를 유지했다. — 이번 단계는 Controller/Repository 이전의 순수 도메인 로직
- [x] 프로젝트의 Java·Spring Boot·Gradle 버전을 따랐다.
- [x] 프로젝트에서 사용하지 않는 JPA·MyBatis·WebFlux·Swagger/OpenAPI를 임의로 추가하지 않았다. — 신규 외부 라이브러리(tokenizer, Markdown 파서) 추가 없음
- [x] 클래스·메서드 역할과 필요한 파라미터·예외 주석을 작성했다.
- [x] Service 레이어의 주요 흐름과 경고 로그를 작성했다. — `DocumentSourceReader`에 `@Slf4j` 경고 로그(디렉터리 없음 등)
- [x] 비즈니스 예외가 있으면 프로젝트의 단일 예외 체계를 사용했다. — 읽기 실패 시 `RagException`
- [x] 민감 정보와 자격 증명을 코드·설정·로그에 기록하지 않았다.

## 3. 테스트·검증

- [x] 변경 계층에 대응하는 테스트를 작성하거나 수정했다. — `DocumentPropertiesTest`, `DocumentSourceReaderTest`, `MarkdownParserTest`, `MarkdownChunkerTest`, `DocumentPipelineIntegrationTest`
- [x] `./gradlew test --no-daemon`를 실행했다.
- [x] 테스트 결과를 실제 출력으로 확인했다. — `BUILD SUCCESSFUL`, 33 tests, 0 failures (기존 embedding 테스트 8개 포함, 회귀 없음)
- [x] 필요하면 Health Check·DB·외부 호출 등 변경된 경계를 별도로 검증했다. — 실제 `docs/` 문서 7개(총 117 Chunk)로 파이프라인을 수동 검증한 뒤 임시 테스트 파일 삭제
- [ ] claude-kit Stop Hook의 컴파일·대응 테스트 결과를 확인했다. — 세션에서 훅 실행 로그를 직접 확인하지 못함, 수동 테스트로 대체
- [x] 테스트·빌드 실패를 남긴 채 완료 처리하지 않았다. — 1건 실패(overlap 계산 시 trim 전/후 불일치 버그) 즉시 수정 후 재검증

## 4. 문서·사람 검수

- [x] API 경로·메서드·요청·응답이 바뀌면 README API 목록을 갱신했다. — 해당 없음 (신규 엔드포인트 없음)
- [x] 프로젝트에서 관리하는 API 명세가 있으면 갱신했다. — 해당 없음
- [x] DB 테이블·컬럼·인덱스가 바뀌면 migration과 관련 문서를 함께 갱신했다. — 해당 없음 (DB 미접근 단계)
- [x] 설계 결정이나 구현 현황이 바뀌면 참고 문서를 갱신했다. — `docs/rag_mvp_development_plan.md` 4단계 체크박스 및 결정된 수치·정책 갱신
- [ ] claude-kit Review Hook의 검수 결과를 확인했다. — 미확인, 사람 검수 필요
- [ ] Critical·High 위험 변경은 사람이 검수했다. — 아래 "사람 검수가 필요한 사항" 참고
- [x] 외부 부작용·권한·트랜잭션·동시성·삭제·시크릿 변경 여부를 직접 확인했다. — 파일 읽기(로컬 디렉터리)만 수행, 외부 API·DB 접근 없음

## 5. 완료 기록

- 변경 파일:
  - `src/main/resources/application.yml` (`document.*` 설정 추가)
  - `src/main/java/com/nohtaehwan/rag/document/DocumentProperties.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/document/SourceFile.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/document/DocumentSourceReader.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/document/LengthMeasurer.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/document/CharacterLengthMeasurer.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/document/DocumentContent.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/document/MarkdownParser.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/document/DocumentChunk.java` (신규)
  - `src/main/java/com/nohtaehwan/rag/document/MarkdownChunker.java` (신규)
  - `src/test/java/com/nohtaehwan/rag/document/DocumentPropertiesTest.java` (신규)
  - `src/test/java/com/nohtaehwan/rag/document/DocumentSourceReaderTest.java` (신규)
  - `src/test/java/com/nohtaehwan/rag/document/MarkdownParserTest.java` (신규)
  - `src/test/java/com/nohtaehwan/rag/document/MarkdownChunkerTest.java` (신규)
  - `src/test/java/com/nohtaehwan/rag/document/DocumentPipelineIntegrationTest.java` (신규)
  - `docs/rag_mvp_development_plan.md` (4단계 체크박스, 결정 수치·정책 갱신)
- 실행한 검증 명령:
  - `./gradlew compileJava compileTestJava`
  - `./gradlew test --no-daemon`
  - `git status --short --untracked-files=all`, `git diff --check`
- 실제 검증 결과:
  - `./gradlew test --no-daemon` → `BUILD SUCCESSFUL`, 33 tests, 0 failures (document 패키지 22개 + 기존 embedding/health 11개)
  - 실제 `docs/*.md` 7개 파일 수동 검증 → 예외 없음, 빈 Chunk 없음, 총 117 Chunk 생성
  - `git diff --check` → 공백 관련 이슈 없음
- 미완료 항목과 사유:
  - claude-kit Stop/Review Hook 결과 미확인 — 세션에서 훅 실행 로그를 직접 확인하지 못함, 수동 테스트로 대체
- 사람 검수가 필요한 사항:
  - 길이 측정을 문자 수 근사치로 결정한 것(target 1200자/overlap 150자/최소 80자)이 실제 BGE-M3 tokenizer 기준과 얼마나 차이 나는지 — 5단계 이후 실제 검색 품질로 검증 필요
  - `MarkdownChunker`의 문단 오버플로우 시 "문장 단위" 대신 "문자 단위" 최후 분할만 구현한 결정 — 한국어/영어 혼용 문서에서 문장 경계 탐지 신뢰도가 낮다고 판단해 의도적으로 생략함, 검색 품질에 문제가 확인되면 재검토
  - `chunkKey`를 `sourceKey#index`(content hash 미사용)로 설계한 것 — 5단계 idempotent 색인 구현 시 이 계약으로 충분한지 재확인 필요

## 6. ponytail-review 반영 (과잉구현 관점 검토 후속 조치)

`/ponytail-review`(diff 대상) 결과 4건(interface 단일 구현체, 죽은 방어 필터, 중복 fence 판정 로직, codePoint 기반 연산 과잉) 중 사람 판단으로 아래 3건을 반영했다.

- **delete** — `DocumentSourceReader.readAll()`의 `path.normalize().startsWith(base)` 필터 제거. `Files.walk(base)`가 기본적으로 symlink를 따라가지 않고 바로 위 줄에서 symlink 파일도 걸러내므로 base 밖 경로가 나올 수 없는, 항상 true인 죽은 방어 코드였음
- **shrink** — `MarkdownChunker.isFence()`가 `FENCE_PATTERN`과 별개로 `startsWith("```")`/`startsWith("~~~")`를 직접 구현하던 것을 `FENCE_PATTERN.matcher(block.strip()).find()` 재사용으로 축소
- **native** — `MarkdownChunker.tail()`/`splitByCharacters()`의 `codePointCount`/`offsetByCodePoints` 기반 서로게이트 쌍 방어 로직을 일반 `String` 인덱스(`substring`) 연산으로 단순화. 한글은 BMP 내 단일 UTF-16 코드유닛이라 서로게이트 쌍 걱정이 불필요한 과잉 방어였음

미반영(범위 밖으로 판단, 사람이 유지 결정):
- `LengthMeasurer` 인터페이스(구현체 `CharacterLengthMeasurer` 하나뿐, yagni 지적) — 향후 실제 tokenizer 구현체로 교체할 수 있도록 의도적으로 유지하기로 결정

**변경 파일 (추가)**
- `src/main/java/com/nohtaehwan/rag/document/DocumentSourceReader.java` (죽은 필터 제거)
- `src/main/java/com/nohtaehwan/rag/document/MarkdownChunker.java` (`isFence`/`tail`/`splitByCharacters` 단순화)

**검증 결과**
- `./gradlew test --no-daemon` → `BUILD SUCCESSFUL`, 33 tests, 0 failures (회귀 없음)

## 7. 다른 에이전트 코드리뷰 반영 (2026-09-03, 4건)

다른 에이전트가 4단계 구현을 리뷰해 4가지 수정 요청을 전달했다. 범위는 `MarkdownParser`, `MarkdownChunker`, `DocumentSourceReader`와 대응 테스트로 한정했고, Embedding/DB/API/5단계는 건드리지 않았다.

### 1) Chunk 목표 길이 초과 버그 수정 — `MarkdownChunker.pack()`

- **원인**: overlap을 다음 chunk 앞에 붙인 뒤(`overlapTail + block`) 그 결과를 targetLength와 다시 비교하지 않아, `overlap + block`이 target을 넘어도 그대로 다음 buffer로 쓰였다.
- **수정**: overlap을 붙인 결과(`next`)가 target을 넘으면 overlap을 생략하고 원본 block만 사용하도록 변경. targetLength가 overlap보다 우선.
- **추가 설계 변경**: target을 넘는 단일 문단(코드 블록 제외)은 `pack()`이 즉시 `splitByCharacters()`로 분할해 최종 chunk로 직접 추가하도록 바꿔, 이후 문단 packing 단계에서 overlap이 중복 적용되지 않게 했다(예전에는 분할된 조각이 다시 일반 buffer 로직을 거쳐 overlap이 두 번 적용될 수 있었다).
- **테스트**: `chunk_generalChunksNeverExceedTargetLength_forVeryLongParagraphWithOverlap`, `chunk_generalChunksNeverExceedTargetLength_forMultipleNearTargetParagraphs`, `chunk_splitByCharactersAppliesOverlapExactlyOnce_notDoubled`, 기존 `chunk_keepsFencedCodeBlockAtomic_evenWhenOversized`를 확장해 코드 블록만 예외적으로 초과 가능함을 같이 검증.

### 2) Fenced code block 판별 정확성 개선 — `FenceTracker` 신규

- **원인**: `^(```+|~~~+)`라는 단순 패턴으로 fence 상태를 toggle해, backtick↔tilde mismatch, 짧은 closing fence, closing 뒤 trailing text가 있는 경우를 모두 "닫힘"으로 오인했다.
- **수정**: `FenceTracker`(신규, package-private)를 만들어 여는 fence의 종류·길이를 기억하고, 같은 종류·길이 이상·trailing text 없음을 모두 만족해야 닫힘으로 인정하도록 구현. `MarkdownParser`와 `MarkdownChunker`가 이 클래스 하나를 공유해 판정 로직 중복을 제거했다.
- **테스트**: `FenceTrackerTest` 신규(정상 backtick/tilde, mismatched, 짧은 closing, trailing text, unclosed). `MarkdownParserTest`에 mismatched-fence-내부-heading, unclosed-fence 케이스 추가. `MarkdownChunkerTest`에 `chunk_doesNotSplitCodeBlockOnMismatchedInnerFenceLine` 추가.

### 3) 짧은 Chunk 처리 정책 보완 — `MarkdownChunker.mergeShort()`

- **원인**: 첫 Chunk가 minLength보다 짧으면 이전 Chunk가 없어 병합 없이 그대로 남았다(암묵적 "첫 Chunk 예외 허용" 상태였으나 명문화되지 않음).
- **수정**: 이전 Chunk가 없는 경우 다음 Chunk로 forward 병합을 시도하도록 추가. 모든 병합은 결과가 targetLength를 넘지 않을 때만 수행하고, fenced code block은 병합 대상/받는 대상 모두에서 제외. 병합 대상이 전혀 없으면(section에 Chunk가 하나뿐이면) 짧아도 그대로 유지.
- **테스트**: `mergeShort_lastChunkShort_mergesBackwardIntoPrevious`, `mergeShort_firstChunkShort_mergesForwardIntoNext`, `mergeShort_onlyShortChunkInSection_staysStandalone`, `mergeShort_doesNotMergeAcrossSectionBoundaries`.

### 4) 파일 수집·파싱·설정 바인딩 테스트 보완

- `.md` 확장자 비교가 대소문자를 구분함을 명시하고 테스트(`readAll_extensionMatchIsCaseSensitive_uppercaseMdIgnored`).
- 존재하지 않는 디렉터리(기존)와 별도로, 존재하지만 빈 디렉터리 케이스 추가(`readAll_returnsEmptyList_whenDirectoryExistsButIsEmpty`).
- `document.*` 설정이 실제 Spring Boot Context에서 `application.yml` 기본값대로 바인딩되는지 `@SpringBootTest`로 검증(`DocumentPropertiesBindingTest` 신규).
- UTF-8 BOM 제거(`DocumentSourceReader.readFile()`에 추가) 및 테스트(`readAll_stripsLeadingUtf8Bom`).
- 닫히지 않은 front matter는 일반 본문으로 취급됨을 테스트(`parse_unclosedFrontMatter_isTreatedAsRegularBody`).
- 지시대로 전체 Markdown 문법 parser나 외부 tokenizer는 추가하지 않았다.

### 5) Git 상태 확인 — `.claude/` staged deletion

이전 작업에서 스테이징된 `.claude/` 13개 파일 삭제(hooks/review/settings.json/skills)는 이번 작업과 무관하며, **이번 코드리뷰 반영 과정에서 건드리지 않았다.** `git reset`, `git rm -f`, `rm -rf` 등 강제·파괴적 명령도 실행하지 않았다.

**변경 파일 (전체)**
- `src/main/java/com/nohtaehwan/rag/document/FenceTracker.java` (신규)
- `src/main/java/com/nohtaehwan/rag/document/MarkdownParser.java` (FenceTracker 사용으로 교체)
- `src/main/java/com/nohtaehwan/rag/document/MarkdownChunker.java` (pack() invariant 수정, FenceTracker 사용, mergeShort forward 병합)
- `src/main/java/com/nohtaehwan/rag/document/DocumentSourceReader.java` (BOM 제거, 대소문자 구분 문서화)
- `src/test/java/com/nohtaehwan/rag/document/FenceTrackerTest.java` (신규)
- `src/test/java/com/nohtaehwan/rag/document/MarkdownParserTest.java` (fence/front matter 케이스 추가)
- `src/test/java/com/nohtaehwan/rag/document/MarkdownChunkerTest.java` (invariant/overlap/mergeShort 케이스 대폭 추가)
- `src/test/java/com/nohtaehwan/rag/document/DocumentSourceReaderTest.java` (확장자/빈 디렉터리/BOM 케이스 추가)
- `src/test/java/com/nohtaehwan/rag/document/DocumentPropertiesBindingTest.java` (신규, `@SpringBootTest`)
- `docs/rag_mvp_development_plan.md` (분할/병합/fence/파일수집 정책 문서화)

**검증 결과 (이번 라운드)**
- `./gradlew test --no-daemon` → `BUILD SUCCESSFUL`, **55 tests, 0 failures** (기존 33개 포함 전체 회귀 없음)
- `git diff --check` → 공백 이슈 없음
- `git status --short --untracked-files=all` → `.claude/` staged deletion 13개 그대로 유지, 이번 작업 관련 파일만 추가로 변경/추가됨
- 실제 `docs/*.md` 8개 파일(349 chunk)로 수동 재검증 → 빈 chunk 없음, **일반 chunk의 targetLength 초과 0건**(코드 블록만 예외). 이전 검증(117 chunk)보다 chunk 수가 늘었는데, 이는 수정 전 `pack()`이 invariant를 어기고 실제로는 target을 넘는 "더 적지만 잘못된" chunk를 만들고 있었기 때문 — 이번 수정으로 정상화된 결과다.
- 임시 수동 검증 테스트 파일은 확인 후 삭제

**커밋/push**: 실행하지 않았다.

```text
CLAUDE.md·관련 Skill 확인
→ 테스트 작성·수정
→ ./gradlew test 통과
→ Stop Hook 결과 확인
→ 문서 동기화
→ 위험 변경 사람 검수
→ 완료 기록 작성
```

Stop Hook 결과 확인과 사람 검수는 미완료 상태로 남겨두며, 위 "사람 검수가 필요한 사항"을 사용자에게 별도 보고한다. 커밋·push는 하지 않았다.
