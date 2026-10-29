import { forwardRef, type FormEvent, type KeyboardEvent } from 'react';

type Props = {
  value: string;
  onChange: (value: string) => void;
  onSubmit: (question: string) => void;
  /** 답변 준비 중 등 제출을 막아야 할 때 true. */
  busy: boolean;
};

/**
 * 질문 입력창. Enter 전송, Shift+Enter 줄바꿈, 한글 조합 중 Enter 무시,
 * 공백만 입력·busy 상태에서는 제출하지 않는다.
 */
const Composer = forwardRef<HTMLTextAreaElement, Props>(function Composer({ value, onChange, onSubmit, busy }, ref) {
  const question = value.trim();
  const disabled = busy || question === '';

  function submit(e?: FormEvent) {
    e?.preventDefault();
    if (!disabled) onSubmit(question);
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      submit();
    }
  }

  return (
    <div className="composer-area">
      <div className="composer-shell">
        <form className="composer" onSubmit={submit}>
          <textarea
            ref={ref}
            aria-label="질문 입력"
            placeholder="문서에 대해 궁금한 점을 물어보세요"
            rows={1}
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={onKeyDown}
          />
          <div className="composer-bottom">
            <span className="scope-pill">기본 지식 공간</span>
            <button type="submit" className="send" aria-label="질문 보내기" disabled={disabled}>
              <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 19V5m0 0-6 6m6-6 6 6" /></svg>
            </button>
          </div>
        </form>
        <div className="composer-hint">Enter로 전송 · Shift + Enter로 줄바꿈 · 질문마다 따로 답하며 이전 대화는 참고하지 않습니다</div>
      </div>
    </div>
  );
});

export default Composer;
