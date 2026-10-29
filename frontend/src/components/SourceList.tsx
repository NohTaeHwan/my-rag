import { useId } from 'react';

/** 답변 API의 출처 메타데이터. 원문 미리보기는 표시하지 않는다. */
export type Source = {
  documentId: number;
  title: string;
  chunkIndex: number;
};

/** 답변 아래의 출처 목록. 제목·문서 ID·구간 번호만 보여준다. */
export default function SourceList({ sources }: { sources: Source[] }) {
  // 답변마다 SourceList가 렌더되므로 제목 id는 인스턴스별로 고유해야 한다.
  const titleId = useId();
  return (
    <section className="references">
      <h3 className="references-title" id={titleId}>참고한 문서</h3>
      <ul className="source-list" aria-labelledby={titleId}>
        {sources.map((source) => (
          <li className="source" key={`${source.documentId}-${source.chunkIndex}`}>
            <span className="source-icon" aria-hidden="true">
              <svg className="icon" viewBox="0 0 24 24"><path d="M6 3h9l4 4v14H6zM15 3v5h4M9 13h7M9 17h5" /></svg>
            </span>
            <span className="source-copy">
              <b>{source.title}</b>
              <small>문서 #{source.documentId} · 구간 {source.chunkIndex}</small>
            </span>
          </li>
        ))}
      </ul>
    </section>
  );
}
