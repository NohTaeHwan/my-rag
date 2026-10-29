import type { AnswerError } from '../api/answers';
import SourceList, { type Source } from './SourceList';

/** 질문 하나와 그 답변 상태(loading → answer | error). 여러 질문을 표시해도 이전 질문은 API에 보내지 않는다. */
export type Turn =
  | { question: string; status: 'loading' }
  | { question: string; status: 'error'; kind: AnswerError['kind'] }
  | { question: string; status: 'answer'; answer: string; sources: Source[] };

/** 오류 종류별 고정 안내. 서버 응답 본문은 표시하지 않는다. */
const ERROR_MESSAGES: Record<AnswerError['kind'], string> = {
  'bad-request': '질문을 처리할 수 없습니다. 질문 내용을 확인해 주세요.',
  'not-found': '지식 공간을 찾을 수 없거나 사용할 수 없습니다.',
  server: '답변을 만드는 중 서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.',
  network: '서버에 연결하지 못했습니다. 네트워크 상태를 확인한 뒤 다시 시도해 주세요.',
  format: '서버 응답 형식을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.',
  timeout: '응답 시간이 초과되었습니다(120초). 서버에서 처리가 계속될 수 있으니 잠시 후 다시 시도해 주세요.',
};

const SUGGESTIONS = [
  { label: '핵심 내용 요약하기', question: '이 문서의 핵심 내용을 요약해줘' },
  { label: '주의할 점 찾아보기', question: '이 문서에서 주의해야 할 점은?' },
];

const mark = (
  <svg className="icon" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 4h14v14H9l-4 3V4z" /><path d="M9 9h6M9 13h4" /></svg>
);

function TurnResult({ turn }: { turn: Turn }) {
  switch (turn.status) {
    case 'loading':
      return (
        <div className="loading" role="status">
          <span className="dots" aria-hidden="true"><i /><i /><i /></span>
          관련 문서를 찾고 답변을 준비하고 있습니다…
        </div>
      );
    case 'error':
      return (
        <div className="error" role="alert">
          <b>답변을 불러오지 못했습니다.</b>
          {ERROR_MESSAGES[turn.kind]} 질문은 이 화면에 남아 있으며 다시 보낼 수 있습니다.
        </div>
      );
    case 'answer':
      return (
        <>
          <div className="answer-text">
            {turn.answer.split(/\n{2,}/).map((paragraph, i) => <p key={i}>{paragraph}</p>)}
          </div>
          {turn.sources.length > 0
            ? <SourceList sources={turn.sources} />
            : <p className="answer-note">이 답변에 연결된 출처가 없습니다.</p>}
        </>
      );
  }
}

type Props = {
  turns: Turn[];
  onSuggest: (question: string) => void;
};

/** 대화 영역: 질문이 없으면 빈 화면, 있으면 질문과 답변 상태를 순서대로 보여준다. */
export default function ChatView({ turns, onSuggest }: Props) {
  return (
    <div className="thread" role="log" aria-label="대화" aria-live="polite">
      {turns.length === 0 ? (
        <div className="empty">
          <div className="empty-overline">내 문서를 더 깊이 이해하는 방법</div>
          <h2>읽어둔 문서에,<br />다시 질문하세요.</h2>
          <p>지식 공간의 문서에서 근거를 찾고, 출처와 함께 답합니다.</p>
          <div className="suggestions">
            {SUGGESTIONS.map((s) => (
              <button type="button" key={s.label} onClick={() => onSuggest(s.question)}>{s.label} ↗</button>
            ))}
          </div>
        </div>
      ) : (
        turns.map((turn, i) => (
          <div className="turn" key={i}>
            <div className="user-row"><div className="user-bubble">{turn.question}</div></div>
            <div className="answer-layout">
              <span className="answer-mark">{mark}</span>
              <div className="answer-content">
                <div className="answer-head">문서와 대화{turn.status === 'loading' ? ' · 답변 준비 중' : ''}</div>
                <TurnResult turn={turn} />
              </div>
            </div>
          </div>
        ))
      )}
    </div>
  );
}
