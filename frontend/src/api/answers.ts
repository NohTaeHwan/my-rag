import type { Source } from '../components/SourceList';

/** 현재 MVP의 기본 Knowledge Base ID. 화면의 '기본 지식 공간'은 이 ID의 표시 이름일 뿐이다. */
export const DEFAULT_KNOWLEDGE_BASE_ID = 1;

/** 답변 대기 한도(LLM 60초 + 검색·네트워크 여유). 클라이언트만 기다림을 멈추며 서버 처리 취소는 보장하지 않는다. */
export const ANSWER_TIMEOUT_MS = 120_000;

/** 화면에 쓰는 답변. 출처는 표시 필드만 남긴다(source·distance는 버림). */
export type Answer = {
  answer: string;
  sources: Source[];
};

/**
 * 답변 요청 실패 종류. 서버 응답 본문(오류 상세·경로 등)은 담지 않는다.
 * - bad-request(400), not-found(404), server(그 외 비정상 HTTP), network(fetch 실패), format(200이지만 형식 불일치),
 *   timeout(ANSWER_TIMEOUT_MS 안에 응답 없음)
 */
export class AnswerError extends Error {
  constructor(readonly kind: 'bad-request' | 'not-found' | 'server' | 'network' | 'format' | 'timeout') {
    super(`answer request failed: ${kind}`);
    this.name = 'AnswerError';
  }
}

const isInt = (v: unknown): v is number => Number.isInteger(v);

function parseAnswer(body: unknown): Answer {
  const b = body as { answer?: unknown; sources?: unknown } | null;
  if (typeof b?.answer !== 'string' || !Array.isArray(b.sources)) throw new AnswerError('format');
  const sources = b.sources.map((s: { documentId?: unknown; title?: unknown; chunkIndex?: unknown } | null) => {
    if (!isInt(s?.documentId) || typeof s?.title !== 'string' || !isInt(s?.chunkIndex)) throw new AnswerError('format');
    return { documentId: s.documentId, title: s.title, chunkIndex: s.chunkIndex };
  });
  return { answer: b.answer, sources };
}

/**
 * 같은 출처 {@code POST /api/answers}로 질문을 보낸다. 사용자 ID·이전 대화는 보내지 않는다.
 * fetch가 신호를 따르지 않아도 취소·타임아웃 시점에 바로 끝나며, 끝나면 타이머를 제거한다.
 *
 * @param question  공백이 아닌 질문(호출 측에서 trim)
 * @param signal    새 대화 등으로 요청을 취소할 신호. 취소되면 AbortError를 그대로 던진다.
 * @param timeoutMs 응답 대기 한도
 * @throws AnswerError 비정상 HTTP·네트워크 실패·응답 형식 불일치·타임아웃
 */
export async function requestAnswer(question: string, signal: AbortSignal, timeoutMs = ANSWER_TIMEOUT_MS): Promise<Answer> {
  if (signal.aborted) throw signal.reason; // 이미 취소됐으면 요청·타이머를 만들지 않는다
  const inner = new AbortController();
  let timer: ReturnType<typeof setTimeout> | undefined;
  let onAbort = () => {};
  const guard = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      reject(new AnswerError('timeout'));
      inner.abort();
    }, timeoutMs);
    onAbort = () => {
      reject(signal.reason);
      inner.abort();
    };
  });
  signal.addEventListener('abort', onAbort, { once: true });
  try {
    return await Promise.race([fetchAnswer(question, inner.signal), guard]);
  } finally {
    clearTimeout(timer);
    signal.removeEventListener('abort', onAbort);
  }
}

async function fetchAnswer(question: string, signal: AbortSignal): Promise<Answer> {
  let response: Response;
  try {
    response = await fetch('/api/answers', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ question, knowledgeBaseId: DEFAULT_KNOWLEDGE_BASE_ID }),
      signal,
    });
  } catch (e) {
    if (signal.aborted) throw e;
    throw new AnswerError('network');
  }
  if (signal.aborted) throw signal.reason; // 취소·타임아웃 뒤 늦게 온 응답은 읽지 않는다(이미 guard로 끝남)
  if (!response.ok) {
    throw new AnswerError(response.status === 400 ? 'bad-request' : response.status === 404 ? 'not-found' : 'server');
  }
  let body: unknown;
  try {
    body = await response.json();
  } catch (e) {
    if (signal.aborted) throw e;
    throw new AnswerError('format');
  }
  return parseAnswer(body);
}
