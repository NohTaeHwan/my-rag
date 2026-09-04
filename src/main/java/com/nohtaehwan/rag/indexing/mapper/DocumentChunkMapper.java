package com.nohtaehwan.rag.indexing.mapper;

import java.util.List;

import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * tb_document_chunk에 대한 MyBatis mapper. 고정 SQL은 없고(문서 수만큼 VALUES 절이 늘어나는
 * multi-row INSERT라 정적 annotation SQL로 표현 불가), {@link DocumentChunkSqlProvider}가 순수
 * Java로 SQL 문자열을 만든다. XML은 쓰지 않는다.
 */
@Mapper
public interface DocumentChunkMapper {

    /**
     * 한 문서에 속한 Chunk 전체를 한 번의 multi-row INSERT로 저장한다. 호출하는 쪽(Repository)이
     * 빈 목록이면 호출하지 않는 책임을 진다 — 이 메서드는 빈 목록에 대해 별도로 방어하지 않는다.
     */
    @InsertProvider(type = DocumentChunkSqlProvider.class, method = "insertAll")
    void insertAll(@Param("documentId") Long documentId, @Param("chunks") List<ChunkRow> chunks);
}
