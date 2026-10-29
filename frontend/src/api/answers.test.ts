import { afterEach, expect, test, vi } from 'vitest';
import { ANSWER_TIMEOUT_MS, AnswerError, DEFAULT_KNOWLEDGE_BASE_ID, requestAnswer } from './answers';

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

const json = (status: number, body: unknown) =>
  new Response(typeof body === 'string' ? body : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

function stubFetch(impl: (...args: Parameters<typeof fetch>) => Promise<Response>) {
  const fetchMock = vi.fn(impl);
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

async function errorKind(promise: Promise<unknown>) {
  const error = await promise.then(() => null, (e: unknown) => e);
  expect(error).toBeInstanceOf(AnswerError);
  return (error as AnswerError).kind;
}

test('같은 출처 POST /api/answers로 질문과 기본 KB ID만 JSON으로 보낸다', async () => {
  const fetchMock = stubFetch(async () => json(200, { answer: 'a', sources: [] }));
  const signal = new AbortController().signal;

  await requestAnswer('질문', signal);

  expect(DEFAULT_KNOWLEDGE_BASE_ID).toBe(1);
  expect(fetchMock).toHaveBeenCalledTimes(1);
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe('/api/answers');
  expect(init?.method).toBe('POST');
  expect(init?.headers).toEqual({ 'Content-Type': 'application/json' });
  expect(JSON.parse(init?.body as string)).toEqual({ question: '질문', knowledgeBaseId: 1 });
  expect(init?.signal).toBeInstanceOf(AbortSignal);
  expect(init?.signal?.aborted).toBe(false);
});

test('정상 응답은 답변과 출처의 표시 필드(documentId·title·chunkIndex)만 돌려준다', async () => {
  stubFetch(async () =>
    json(200, {
      answer: '첫 줄\n둘째 줄',
      sources: [{ documentId: 3, title: 'guide.md', source: 'private/path/guide.md', chunkIndex: 0, distance: 0.21 }],
    }),
  );

  await expect(requestAnswer('q', new AbortController().signal)).resolves.toEqual({
    answer: '첫 줄\n둘째 줄',
    sources: [{ documentId: 3, title: 'guide.md', chunkIndex: 0 }],
  });
});

test('빈 sources는 정상 답변이다', async () => {
  stubFetch(async () => json(200, { answer: '근거가 부족합니다.', sources: [] }));

  await expect(requestAnswer('q', new AbortController().signal)).resolves.toEqual({ answer: '근거가 부족합니다.', sources: [] });
});

test.each([
  ['JSON이 아님', '<html>oops</html>'],
  ['answer 없음', { sources: [] }],
  ['sources가 배열이 아님', { answer: 'a', sources: null }],
  ['출처 title 누락', { answer: 'a', sources: [{ documentId: 1, chunkIndex: 0 }] }],
  ['출처 documentId가 정수 아님', { answer: 'a', sources: [{ documentId: '1', title: 't', chunkIndex: 0 }] }],
  ['출처 chunkIndex가 정수 아님', { answer: 'a', sources: [{ documentId: 1, title: 't', chunkIndex: 1.5 }] }],
])('200이어도 형식이 맞지 않으면(%s) format 오류', async (_, body) => {
  stubFetch(async () => json(200, body));

  expect(await errorKind(requestAnswer('q', new AbortController().signal))).toBe('format');
});

test.each([
  [400, 'bad-request'],
  [404, 'not-found'],
  [500, 'server'],
  [503, 'server'],
])('HTTP %i는 response.ok로 판별해 %s 오류로 바꾸고 서버 본문을 오류에 싣지 않는다', async (status, kind) => {
  stubFetch(async () => json(status, { message: 'debug /home/user/secret stacktrace' }));

  const error = await requestAnswer('q', new AbortController().signal).catch((e: unknown) => e);
  expect(error).toBeInstanceOf(AnswerError);
  expect((error as AnswerError).kind).toBe(kind);
  expect(String((error as Error).message)).not.toContain('secret');
});

test('fetch 자체가 실패하면 network 오류', async () => {
  stubFetch(async () => {
    throw new TypeError('Failed to fetch');
  });

  expect(await errorKind(requestAnswer('q', new AbortController().signal))).toBe('network');
});

test('취소 신호로 중단되면 AnswerError가 아니라 AbortError를 그대로 던진다', async () => {
  stubFetch((_, init) =>
    new Promise((_, reject) => {
      init?.signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')));
    }),
  );
  const controller = new AbortController();

  const pending = requestAnswer('q', controller.signal);
  controller.abort();

  await expect(pending).rejects.toMatchObject({ name: 'AbortError' });
});

// ── 타임아웃(가짜 타이머). fetch 모의는 신호를 무시해 서버 무응답을 재현한다. ──

const hangingFetch = () => stubFetch(() => new Promise<Response>(() => {}));

test('기본 타임아웃은 120초이고, 무응답이면 timeout 오류로 끝나며 내부 요청 신호를 중단한다', async () => {
  vi.useFakeTimers();
  const fetchMock = hangingFetch();
  expect(ANSWER_TIMEOUT_MS).toBe(120_000);

  const pending = requestAnswer('q', new AbortController().signal).catch((e: unknown) => e);
  await vi.advanceTimersByTimeAsync(ANSWER_TIMEOUT_MS - 1);
  expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(false);
  await vi.advanceTimersByTimeAsync(1);

  const error = await pending;
  expect(error).toBeInstanceOf(AnswerError);
  expect((error as AnswerError).kind).toBe('timeout');
  expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true);
  expect(vi.getTimerCount()).toBe(0);
});

test('정상 완료 시 타임아웃 타이머를 제거한다', async () => {
  vi.useFakeTimers();
  stubFetch(async () => json(200, { answer: 'a', sources: [] }));

  await requestAnswer('q', new AbortController().signal);

  expect(vi.getTimerCount()).toBe(0);
});

test('호출 측 취소는 fetch가 신호를 무시해도 즉시 AbortError로 끝나고 타이머를 제거한다', async () => {
  vi.useFakeTimers();
  const fetchMock = hangingFetch();
  const controller = new AbortController();

  const pending = requestAnswer('q', controller.signal).catch((e: unknown) => e);
  controller.abort();

  expect(await pending).toMatchObject({ name: 'AbortError' });
  expect(fetchMock.mock.calls[0][1]?.signal?.aborted).toBe(true);
  expect(vi.getTimerCount()).toBe(0);
});

test('진입 전에 이미 취소된 신호면 fetch를 호출하지 않고 즉시 signal.reason으로 끝나며 타이머를 남기지 않는다', async () => {
  vi.useFakeTimers();
  const fetchMock = hangingFetch();
  const controller = new AbortController();
  controller.abort();

  const error = await requestAnswer('q', controller.signal).catch((e: unknown) => e);

  expect(error).toBe(controller.signal.reason);
  expect(error).toMatchObject({ name: 'AbortError' });
  expect(fetchMock).not.toHaveBeenCalled();
  expect(vi.getTimerCount()).toBe(0);
});
