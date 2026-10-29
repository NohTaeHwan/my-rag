import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import App from './App';
import type { Turn } from './components/ChatView';

// 실제 서버로 나가지 않도록 기본 fetch는 끝나지 않는 모의 함수. 시나리오별로 다시 stub한다.
beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>(() => {})));
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

const input = () => screen.getByRole('textbox', { name: '질문 입력' });
const sendButton = () => screen.getByRole('button', { name: '질문 보내기' });
const thread = () => screen.getByRole('log', { name: '대화' });

function type(value: string) {
  fireEvent.change(input(), { target: { value } });
}

test('빈 화면은 기본 지식 공간을 표시하고 답변·출처는 없다', () => {
  render(<App />);

  expect(screen.getByRole('heading', { level: 1, name: '내 문서에 질문하기' })).toBeTruthy();
  expect(screen.getByRole('heading', { name: /읽어둔 문서에/ })).toBeTruthy();
  expect(screen.getAllByText('기본 지식 공간').length).toBeGreaterThan(0);
  expect(screen.queryByText(/답변 연결 전|준비 중입니다/)).toBeNull();
  expect(screen.queryByRole('list', { name: '참고한 문서' })).toBeNull();
});

test('Enter는 질문을 보내고 입력을 비우며 로딩을 표시한다', () => {
  render(<App />);

  type('  첫 질문  ');
  fireEvent.keyDown(input(), { key: 'Enter' });

  expect(within(thread()).getByText('첫 질문')).toBeTruthy();
  expect(within(thread()).getByRole('status').textContent).toMatch(/답변을 준비하고 있습니다/);
  expect((input() as HTMLTextAreaElement).value).toBe('');
  expect(document.activeElement).toBe(input());
  expect(fetch).toHaveBeenCalledTimes(1);
});

test('Shift+Enter와 한글 조합 중 Enter는 전송하지 않는다', () => {
  render(<App />);

  type('줄바꿈');
  expect(fireEvent.keyDown(input(), { key: 'Enter', shiftKey: true })).toBe(true);
  fireEvent.keyDown(input(), { key: 'Enter', isComposing: true });

  expect(within(thread()).queryByText('줄바꿈')).toBeNull();
  expect((input() as HTMLTextAreaElement).value).toBe('줄바꿈');
});

test('공백만 입력하면 전송 버튼이 비활성이고 Enter도 무시한다', () => {
  render(<App />);

  type('   \n ');
  expect((sendButton() as HTMLButtonElement).disabled).toBe(true);
  fireEvent.keyDown(input(), { key: 'Enter' });

  expect(screen.getByRole('heading', { name: /읽어둔 문서에/ })).toBeTruthy();
});

test('답변 준비 중에는 중복 제출을 막고 로딩 상태를 안내한다', () => {
  render(<App initialTurns={[{ question: '이전 질문', status: 'loading' }]} />);

  expect(within(thread()).getByRole('status').textContent).toMatch(/답변을 준비하고 있습니다/);
  type('두 번째 질문');
  expect((sendButton() as HTMLButtonElement).disabled).toBe(true);
  fireEvent.keyDown(input(), { key: 'Enter' });
  fireEvent.submit(input().closest('form')!);

  expect(within(thread()).queryByText('두 번째 질문')).toBeNull();
});

test('오류 상태는 alert로 알리고 질문을 남긴다', () => {
  render(<App initialTurns={[{ question: '실패한 질문', status: 'error', kind: 'server' }]} />);

  expect(screen.getByRole('alert').textContent).toMatch(/답변을 불러오지 못했습니다/);
  expect(within(thread()).getByText('실패한 질문')).toBeTruthy();
});

test('답변 상태는 본문과 출처 메타데이터(제목·문서 ID·구간)만 표시한다', () => {
  const turn: Turn = {
    question: '요약해줘',
    status: 'answer',
    answer: '첫 문단\n\n둘째 문단',
    sources: [{ documentId: 7, title: 'guide.md', chunkIndex: 2 }],
  };
  render(<App initialTurns={[turn]} />);

  expect(within(thread()).getByText('첫 문단')).toBeTruthy();
  expect(within(thread()).getByText('둘째 문단')).toBeTruthy();
  const sources = screen.getByRole('list', { name: '참고한 문서' });
  expect(within(sources).getByText('guide.md')).toBeTruthy();
  expect(within(sources).getByText('문서 #7 · 구간 2')).toBeTruthy();
});

test('새 대화는 로컬 대화와 입력을 비우고 입력창에 포커스를 돌린다', () => {
  render(<App />);

  type('지울 질문');
  fireEvent.keyDown(input(), { key: 'Enter' });
  type('남은 입력');
  fireEvent.click(screen.getByRole('button', { name: '새 대화' }));

  expect(screen.getByRole('heading', { name: /읽어둔 문서에/ })).toBeTruthy();
  expect(within(thread()).queryByText('지울 질문')).toBeNull();
  expect((input() as HTMLTextAreaElement).value).toBe('');
  expect(document.activeElement).toBe(input());
});

test('추천 질문은 입력창만 채우고 전송하지 않는다', () => {
  render(<App />);

  fireEvent.click(screen.getByRole('button', { name: /핵심 내용 요약하기/ }));

  expect((input() as HTMLTextAreaElement).value).toBe('이 문서의 핵심 내용을 요약해줘');
  expect(document.activeElement).toBe(input());
  expect(screen.getByRole('heading', { name: /읽어둔 문서에/ })).toBeTruthy();
});

test('모바일 메뉴는 열면 사이드바로 포커스를 옮기고 Escape·배경 클릭으로 닫히며 메뉴 버튼에 포커스를 돌린다', () => {
  render(<App />);
  const menu = screen.getByRole('button', { name: '메뉴 열기' });

  fireEvent.click(menu);
  expect(menu.getAttribute('aria-expanded')).toBe('true');
  expect(document.activeElement).toBe(screen.getByRole('button', { name: '새 대화' }));

  fireEvent.keyDown(document, { key: 'Escape' });
  expect(menu.getAttribute('aria-expanded')).toBe('false');
  expect(document.activeElement).toBe(menu);

  fireEvent.click(menu);
  fireEvent.click(screen.getByTestId('scrim'));
  expect(menu.getAttribute('aria-expanded')).toBe('false');
});

test('모바일 메뉴가 열린 동안 workspace는 inert이고, 닫으면 inert가 풀린다', () => {
  render(<App />);
  const menu = screen.getByRole('button', { name: '메뉴 열기' });
  const workspace = () => document.querySelector('main.workspace')!;

  fireEvent.click(menu);
  expect(workspace().hasAttribute('inert')).toBe(true);
  expect(workspace().contains(input())).toBe(true);

  fireEvent.keyDown(document, { key: 'Escape' });
  expect(workspace().hasAttribute('inert')).toBe(false);

  fireEvent.click(menu);
  fireEvent.click(screen.getByRole('button', { name: '새 대화' }));
  expect(workspace().hasAttribute('inert')).toBe(false);
  expect(document.activeElement).toBe(input());
});

test('메뉴가 열린 채 데스크톱 폭으로 바뀌면 메뉴를 닫아 workspace가 inert로 남지 않는다', () => {
  const listeners: Array<(e: { matches: boolean }) => void> = [];
  vi.stubGlobal('matchMedia', (query: string) => ({
    matches: true,
    media: query,
    addEventListener: (_: string, fn: (e: { matches: boolean }) => void) => listeners.push(fn),
    removeEventListener: () => {},
  }));
  render(<App />);
  const menu = screen.getByRole('button', { name: '메뉴 열기' });

  fireEvent.click(menu);
  act(() => listeners.forEach((fn) => fn({ matches: false })));

  expect(menu.getAttribute('aria-expanded')).toBe('false');
  expect(document.querySelector('main.workspace')!.hasAttribute('inert')).toBe(false);
});

test('답변이 여러 개여도 각 출처 목록은 자기 제목을 가리킨다', () => {
  const answer = (title: string): Turn => ({
    question: title,
    status: 'answer',
    answer: '본문',
    sources: [{ documentId: 1, title, chunkIndex: 0 }],
  });
  render(<App initialTurns={[answer('a.md'), answer('b.md')]} />);

  const lists = screen.getAllByRole('list', { name: '참고한 문서' });
  expect(lists).toHaveLength(2);
  const ids = lists.map((list) => list.getAttribute('aria-labelledby'));
  expect(new Set(ids).size).toBe(2);
  lists.forEach((list, i) => {
    const heading = document.getElementById(ids[i]!)!;
    expect(heading.textContent).toBe('참고한 문서');
    expect(heading.closest('section')!.contains(list)).toBe(true);
  });
});

// ── 3단계: POST /api/answers 연결(모의 fetch만 사용) ──

const json = (status: number, body: unknown) =>
  new Response(typeof body === 'string' ? body : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  });

/** 응답 시점을 테스트가 정하는 fetch. 신호는 일부러 무시해 늦은 응답 경쟁을 재현할 수 있다. */
function deferredFetch() {
  const calls: Array<{ init?: RequestInit; resolve: (r: Response) => void; reject: (e: unknown) => void }> = [];
  const fetchMock = vi.fn(
    (_: RequestInfo | URL, init?: RequestInit) =>
      new Promise<Response>((resolve, reject) => calls.push({ init, resolve, reject })),
  );
  vi.stubGlobal('fetch', fetchMock);
  return { fetchMock, calls };
}

function ask(question: string) {
  type(question);
  fireEvent.keyDown(input(), { key: 'Enter' });
}

test('정상 응답은 실제 답변 평문(줄바꿈 보존·HTML 미해석)과 출처 표시 필드만 보여준다', async () => {
  const { fetchMock, calls } = deferredFetch();
  render(<App />);

  ask('요약해줘');
  const [url, init] = fetchMock.mock.calls[0];
  expect(url).toBe('/api/answers');
  expect(JSON.parse(init?.body as string)).toEqual({ question: '요약해줘', knowledgeBaseId: 1 });

  await act(async () =>
    calls[0].resolve(
      json(200, {
        answer: '첫 문단 <b>굵게</b>\n둘째 줄\n\n셋째 문단',
        sources: [{ documentId: 7, title: 'guide.md', source: '/home/me/guide.md', chunkIndex: 2, distance: 0.13 }],
      }),
    ),
  );

  expect(within(thread()).queryByRole('status')).toBeNull();
  expect(within(thread()).getByText(/첫 문단 <b>굵게<\/b>/).textContent).toBe('첫 문단 <b>굵게</b>\n둘째 줄');
  expect(within(thread()).getByText('셋째 문단')).toBeTruthy();
  expect(thread().querySelector('b.injected, .answer-text b')).toBeNull();
  const sources = screen.getByRole('list', { name: '참고한 문서' });
  expect(within(sources).getByText('guide.md')).toBeTruthy();
  expect(within(sources).getByText('문서 #7 · 구간 2')).toBeTruthy();
  expect(thread().textContent).not.toMatch(/home\/me|0\.13/);
});

test('빈 sources는 정상 답변으로 보여주고 출처가 없다고 안내한다', async () => {
  const { calls } = deferredFetch();
  render(<App />);

  ask('없는 내용');
  await act(async () => calls[0].resolve(json(200, { answer: '근거가 부족합니다.', sources: [] })));

  expect(within(thread()).getByText('근거가 부족합니다.')).toBeTruthy();
  expect(within(thread()).getByText('이 답변에 연결된 출처가 없습니다.')).toBeTruthy();
  expect(screen.queryByRole('list', { name: '참고한 문서' })).toBeNull();
  expect(screen.queryByRole('alert')).toBeNull();
});

test.each([
  ['형식 오류', () => json(200, { answer: 1 }), /응답 형식을 확인할 수 없습니다/],
  ['400', () => json(400, { message: 'debug /srv/secret' }), /질문을 처리할 수 없습니다/],
  ['404', () => json(404, { message: 'kb 1 owner 42' }), /지식 공간을 찾을 수 없거나 사용할 수 없습니다/],
  ['500', () => json(500, { message: 'LLM stacktrace /srv/secret' }), /서버 오류/],
])('%s는 고정 안내를 alert로 보여주고 로딩을 끝내며 질문을 입력창에 되돌린다', async (_, response, message) => {
  const { calls } = deferredFetch();
  render(<App />);

  ask('실패할 질문');
  await act(async () => calls[0].resolve(response()));

  const alert = screen.getByRole('alert');
  expect(alert.textContent).toMatch(message);
  expect(alert.textContent).not.toMatch(/secret|owner|stacktrace/);
  expect(within(thread()).queryByRole('status')).toBeNull();
  expect(within(thread()).getByText('실패할 질문')).toBeTruthy();
  expect((input() as HTMLTextAreaElement).value).toBe('실패할 질문');
  expect((sendButton() as HTMLButtonElement).disabled).toBe(false);
});

test('네트워크 오류 후 같은 질문을 다시 보낼 수 있다', async () => {
  const { fetchMock, calls } = deferredFetch();
  render(<App />);

  ask('재시도');
  await act(async () => calls[0].reject(new TypeError('Failed to fetch')));
  expect(screen.getByRole('alert').textContent).toMatch(/서버에 연결하지 못했습니다/);

  fireEvent.keyDown(input(), { key: 'Enter' });
  expect(fetchMock).toHaveBeenCalledTimes(2);
  expect(JSON.parse(fetchMock.mock.calls[1][1]?.body as string).question).toBe('재시도');
  await act(async () => calls[1].resolve(json(200, { answer: '두 번째 성공', sources: [] })));
  expect(within(thread()).getByText('두 번째 성공')).toBeTruthy();
});

test('응답 대기 중 Enter·버튼·submit을 반복해도 요청은 한 번만 간다', () => {
  const { fetchMock } = deferredFetch();
  render(<App />);

  ask('한 번만');
  type('또');
  fireEvent.keyDown(input(), { key: 'Enter' });
  fireEvent.click(sendButton());
  fireEvent.submit(input().closest('form')!);

  expect(fetchMock).toHaveBeenCalledTimes(1);
});

test('새 대화는 진행 중 요청을 취소하고, 신호를 무시한 늦은 응답·오류도 새 화면에 나타나지 않는다', async () => {
  const { calls } = deferredFetch();
  render(<App />);

  ask('이전 질문');
  fireEvent.click(screen.getByRole('button', { name: '새 대화' }));
  expect(calls[0].init?.signal?.aborted).toBe(true);
  expect(screen.getByRole('heading', { name: /읽어둔 문서에/ })).toBeTruthy();

  ask('새 질문');
  await act(async () => calls[0].resolve(json(200, { answer: '늦은 이전 답변', sources: [] })));
  expect(within(thread()).queryByText('늦은 이전 답변')).toBeNull();
  expect(within(thread()).getByRole('status')).toBeTruthy();

  await act(async () => calls[1].resolve(json(200, { answer: '새 답변', sources: [] })));
  expect(within(thread()).getByText('새 답변')).toBeTruthy();
  expect(within(thread()).queryByText('이전 질문')).toBeNull();
});

test('새 대화 후 이전 요청의 늦은 오류도 표시하지 않는다', async () => {
  const { calls } = deferredFetch();
  render(<App />);

  ask('이전 질문');
  fireEvent.click(screen.getByRole('button', { name: '새 대화' }));
  await act(async () => calls[0].resolve(json(500, {})));

  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getByRole('heading', { name: /읽어둔 문서에/ })).toBeTruthy();
  expect((input() as HTMLTextAreaElement).value).toBe('');
});

// ── Codex 리뷰 후속: 타임아웃·언마운트·재시도 ──

test('120초 무응답이면 로딩을 끝내고 타임아웃 안내·질문 복원 후 재시도할 수 있으며, 늦은 응답은 화면을 덮지 않는다', async () => {
  vi.useFakeTimers();
  const { fetchMock, calls } = deferredFetch();
  render(<App />);

  ask('느린 질문');
  await act(async () => {
    await vi.advanceTimersByTimeAsync(120_000);
  });

  expect(within(thread()).queryByRole('status')).toBeNull();
  expect(screen.getByRole('alert').textContent).toMatch(/응답 시간이 초과되었습니다/);
  expect((input() as HTMLTextAreaElement).value).toBe('느린 질문');
  expect(calls[0].init?.signal?.aborted).toBe(true);

  await act(async () => calls[0].resolve(json(200, { answer: '늦게 온 답변', sources: [] })));
  expect(within(thread()).queryByText('늦게 온 답변')).toBeNull();

  fireEvent.keyDown(input(), { key: 'Enter' });
  expect(fetchMock).toHaveBeenCalledTimes(2);
});

test('새 대화 후에는 이전 요청의 타임아웃이 발생하지 않는다', async () => {
  vi.useFakeTimers();
  deferredFetch();
  render(<App />);

  ask('이전 질문');
  fireEvent.click(screen.getByRole('button', { name: '새 대화' }));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(120_000);
  });

  expect(screen.queryByRole('alert')).toBeNull();
  expect((input() as HTMLTextAreaElement).value).toBe('');
});

test('언마운트하면 요청을 취소하고, 신호를 무시한 늦은 성공·실패는 처리되지 않는다', async () => {
  const { calls } = deferredFetch();
  const consoleError = vi.spyOn(console, 'error');
  const { unmount } = render(<App />);

  ask('언마운트 질문');
  ask('언마운트 질문');
  unmount();
  expect(calls[0].init?.signal?.aborted).toBe(true);

  const late = json(200, { answer: '늦은 답변', sources: [] });
  const readBody = vi.spyOn(late, 'json');
  await act(async () => calls[0].resolve(late));
  await act(async () => calls[0].reject(new TypeError('late failure')));

  // 요청이 이미 AbortError로 끝나 늦은 응답 본문을 읽지 않는다 → 답변·오류 setState 경로가 실행되지 않음.
  expect(readBody).not.toHaveBeenCalled();
  expect(consoleError).not.toHaveBeenCalled();
});

test('실패한 질문을 그대로 다시 보내면 오류 카드를 로딩→결과로 대체해 중복 누적하지 않는다', async () => {
  const { calls } = deferredFetch();
  render(<App />);

  ask('같은 질문');
  await act(async () => calls[0].resolve(json(500, {})));
  expect(screen.getAllByRole('alert')).toHaveLength(1);

  fireEvent.keyDown(input(), { key: 'Enter' });
  expect(within(thread()).getAllByText('같은 질문')).toHaveLength(1);
  expect(screen.queryByRole('alert')).toBeNull();
  expect(within(thread()).getByRole('status')).toBeTruthy();

  await act(async () => calls[1].resolve(json(500, {})));
  expect(screen.getAllByRole('alert')).toHaveLength(1);
  expect(within(thread()).getAllByText('같은 질문')).toHaveLength(1);

  fireEvent.keyDown(input(), { key: 'Enter' });
  await act(async () => calls[2].resolve(json(200, { answer: '이번엔 성공', sources: [] })));
  expect(within(thread()).getAllByText('같은 질문')).toHaveLength(1);
  expect(screen.queryByRole('alert')).toBeNull();
  expect(within(thread()).getByText('이번엔 성공')).toBeTruthy();
});

test('실패 후 질문을 바꿔 보내면 이전 오류 문맥은 유지하고 새 질문을 추가한다', async () => {
  const { calls } = deferredFetch();
  render(<App />);

  ask('첫 질문');
  await act(async () => calls[0].resolve(json(404, {})));
  ask('바꾼 질문');
  await act(async () => calls[1].resolve(json(200, { answer: '새 답변', sources: [] })));

  expect(within(thread()).getByText('첫 질문')).toBeTruthy();
  expect(screen.getAllByRole('alert')).toHaveLength(1);
  expect(within(thread()).getByText('바꾼 질문')).toBeTruthy();
  expect(within(thread()).getByText('새 답변')).toBeTruthy();
});
