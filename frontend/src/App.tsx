import { useEffect, useRef, useState } from 'react';
import { flushSync } from 'react-dom';
import { AnswerError, requestAnswer } from './api/answers';
import ChatView, { type Turn } from './components/ChatView';
import Composer from './components/Composer';

type Props = {
  /** 테스트에서 로딩·오류·답변 상태를 주입할 때만 쓴다. 제품 화면은 빈 대화로 시작한다. */
  initialTurns?: Turn[];
};

/**
 * 채팅 화면. 질문을 보내면 같은 출처 POST /api/answers(기본 KB ID 1)로 요청하고 답변·출처 또는 오류를 표시한다.
 * 요청은 한 번에 하나이며, 새 대화는 진행 중 요청을 취소하고 늦게 도착한 응답을 버린다.
 */
export default function App({ initialTurns = [] }: Props) {
  const [turns, setTurns] = useState<Turn[]>(initialTurns);
  const [draft, setDraft] = useState('');
  const [menuOpen, setMenuOpen] = useState(false);
  const inputRef = useRef<HTMLTextAreaElement>(null);
  const menuRef = useRef<HTMLButtonElement>(null);
  const newChatRef = useRef<HTMLButtonElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  // 진행 중 요청. 응답 처리 시 이 값과 같을 때만 화면에 반영해 새 대화 이후의 늦은 응답을 버린다.
  const requestRef = useRef<AbortController | null>(null);
  const busy = turns.at(-1)?.status === 'loading';

  // 언마운트 시 취소하고 참조를 비워, 늦게 끝난 요청이 settle 단계에서 버려지게 한다.
  useEffect(
    () => () => {
      requestRef.current?.abort();
      requestRef.current = null;
    },
    [],
  );

  // workspace의 inert가 DOM에서 먼저 풀려야 그 안의 요소로 포커스를 옮길 수 있어 flushSync로 즉시 반영한다.
  function closeMenu() {
    flushSync(() => setMenuOpen(false));
    menuRef.current?.focus();
  }

  // 메뉴가 열린 동안: 사이드바로 포커스, Escape로 닫기, 모바일 폭을 벗어나면 닫아 workspace inert를 푼다.
  useEffect(() => {
    if (!menuOpen) return;
    newChatRef.current?.focus();
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeMenu();
    };
    const mobile = window.matchMedia?.('(max-width: 760px)');
    const onMediaChange = (e: { matches: boolean }) => {
      if (!e.matches) setMenuOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    mobile?.addEventListener('change', onMediaChange);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      mobile?.removeEventListener('change', onMediaChange);
    };
  }, [menuOpen]);

  useEffect(() => {
    scrollRef.current?.scrollTo?.({ top: scrollRef.current.scrollHeight });
  }, [turns]);

  // 입력 내용에 맞춰 textarea 높이를 늘린다(최대 높이는 CSS max-height).
  useEffect(() => {
    const el = inputRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${el.scrollHeight}px`;
  }, [draft]);

  function submit(question: string) {
    if (requestRef.current) return; // 렌더 전 연속 Enter 등 중복 제출 방지
    const controller = new AbortController();
    requestRef.current = controller;
    // 마지막 오류와 같은 질문을 다시 보내면 그 오류 카드를 대체하고, 다른 질문이면 기존 문맥 뒤에 추가한다.
    setTurns((prev) => {
      const last = prev.at(-1);
      const base = last?.status === 'error' && last.question === question ? prev.slice(0, -1) : prev;
      return [...base, { question, status: 'loading' }];
    });
    setDraft('');
    inputRef.current?.focus();

    const settle = (turn: Turn) => {
      if (requestRef.current !== controller) return;
      requestRef.current = null;
      setTurns((prev) => [...prev.slice(0, -1), turn]);
    };
    requestAnswer(question, controller.signal).then(
      (res) => settle({ question, status: 'answer', answer: res.answer, sources: res.sources }),
      (e: unknown) => {
        if (requestRef.current !== controller || controller.signal.aborted) return; // 새 대화·언마운트로 취소·대체된 요청
        settle({ question, status: 'error', kind: e instanceof AnswerError ? e.kind : 'server' });
        // 실패한 질문을 다시 보낼 수 있게 입력창이 비어 있으면 되돌린다.
        setDraft((d) => d || question);
      },
    );
  }

  function startNewChat() {
    requestRef.current?.abort();
    requestRef.current = null;
    flushSync(() => {
      setTurns([]);
      setDraft('');
      setMenuOpen(false);
    });
    inputRef.current?.focus();
  }

  function suggest(question: string) {
    setDraft(question);
    inputRef.current?.focus();
  }

  return (
    <div className="app">
      <aside className={`sidebar${menuOpen ? ' open' : ''}`} id="sidebar" aria-label="사이드바">
        <div className="brand">
          <div className="brand-mark" aria-hidden="true">
            <svg className="icon" viewBox="0 0 24 24"><path d="M5 4h14v14H9l-4 3V4z" /><path d="M9 9h6M9 13h4" /></svg>
          </div>
          문서와 대화
        </div>
        <button type="button" className="new" ref={newChatRef} onClick={startNewChat}>
          <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14" /></svg>
          새 대화
        </button>
        <div className="group">질문 범위</div>
        <div className="scope-card">
          <div className="scope-top">
            <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5h16v14H4zM8 9h8M8 13h6" /></svg>
            기본 지식 공간
          </div>
        </div>
        <p className="scope-note">첫 버전은 하나의 지식 공간(개발용 ID 1)을 사용합니다. 여러 지식 공간 선택은 후속 기능입니다.</p>
        <div className="side-foot">개인 문서 검색 · 답변<span>출처를 함께 확인하세요.</span></div>
      </aside>
      <div className={`scrim${menuOpen ? ' open' : ''}`} data-testid="scrim" onClick={closeMenu} />
      {/* 모바일 메뉴가 열린 동안 가려진 workspace로 Tab 포커스·클릭이 새지 않게 inert로 막는다. */}
      <main className="workspace" inert={menuOpen}>
        <header className="toolbar">
          <div className="toolbar-left">
            <button
              type="button"
              className="menu"
              ref={menuRef}
              aria-label="메뉴 열기"
              aria-controls="sidebar"
              aria-expanded={menuOpen}
              onClick={() => (menuOpen ? closeMenu() : setMenuOpen(true))}
            >
              <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 7h16M4 12h16M4 17h16" /></svg>
            </button>
            <h1 className="toolbar-title">내 문서에 질문하기</h1>
            <span className="toolbar-scope">/ 기본 지식 공간</span>
          </div>
          <div className="toolbar-right">
            <span className="status-dot" aria-hidden="true" />
            지식 공간을 기준으로 답변
          </div>
        </header>
        <div className="scroll" ref={scrollRef}>
          <ChatView turns={turns} onSuggest={suggest} />
        </div>
        <Composer ref={inputRef} value={draft} onChange={setDraft} onSubmit={submit} busy={busy} />
      </main>
    </div>
  );
}
