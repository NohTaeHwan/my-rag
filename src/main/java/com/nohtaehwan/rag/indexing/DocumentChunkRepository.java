package com.nohtaehwan.rag.indexing;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.nohtaehwan.rag.indexing.mapper.ChunkRow;
import com.nohtaehwan.rag.indexing.mapper.DocumentChunkMapper;

/**
 * tb_document_chunk 테이블에 Chunk와 embedding 벡터를 저장한다. 실제 SQL은
 * {@link DocumentChunkMapper}(MyBatis mapper, {@code @InsertProvider})가 수행한다.
 * chunk 개수만큼 늘어나는 multi-row INSERT라 정적 SQL로 못 쓰고, 순수 Java 클래스인
 * {@code DocumentChunkSqlProvider}가 SQL 문자열을 만든다(XML 없음).
 */
@Repository
public class DocumentChunkRepository {

    private final DocumentChunkMapper documentChunkMapper;

    public DocumentChunkRepository(DocumentChunkMapper documentChunkMapper) {
        this.documentChunkMapper = documentChunkMapper;
    }

    /**
     * 문서에 속한 Chunk 전체를 저장한다. embedding은 pgvector 리터럴 문자열로 변환해 파라미터 바인딩한다.
     *
     * @param documentId 소속 tb_document.id
     * @param chunks     저장할 Chunk와 embedding 목록 (빈 목록이면 mapper를 호출하지 않는다)
     */
    public void insertAll(Long documentId, List<EmbeddedChunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        List<ChunkRow> rows = chunks.stream()
                .map(embeddedChunk -> new ChunkRow(
                        embeddedChunk.chunk().content(),
                        embeddedChunk.chunk().index(),
                        toVectorLiteral(embeddedChunk.embedding())))
                .toList();
        documentChunkMapper.insertAll(documentId, rows);
    }

    private static String toVectorLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(",", "[", "]"));
    }
}
