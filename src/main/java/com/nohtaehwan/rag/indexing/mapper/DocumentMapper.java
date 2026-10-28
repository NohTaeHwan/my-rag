package com.nohtaehwan.rag.indexing.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

/**
 * tb_document에 대한 MyBatis mapper. SQL은 annotation에 직접 작성한다(XML 없음).
 */
@Mapper
public interface DocumentMapper {

    /**
     * 주어진 사용자·Knowledge Base 범위 안에서 같은 source의 기존 문서를 삭제한다.
     * tb_document_chunk는 FK ON DELETE CASCADE로 함께 삭제된다. 다른 사용자·KB의 동일
     * source 문서는 범위 조건에 걸리지 않으므로 영향을 받지 않는다(V3 migration).
     *
     * @return 삭제된 행 수
     */
    @Delete("DELETE FROM tb_document "
            + "WHERE user_id = #{userId} AND knowledge_base_id = #{knowledgeBaseId} AND source = #{source}")
    int deleteByUserIdAndKnowledgeBaseIdAndSource(
            @Param("userId") Long userId,
            @Param("knowledgeBaseId") Long knowledgeBaseId,
            @Param("source") String source);

    /**
     * 문서를 insert하고 생성된 id를 parameter.id에 채운다. 이 insert 경로는 색인 시점에
     * embedding까지 이미 끝난 상태로만 호출되므로 index_status를 바로 'INDEXED'로 저장한다
     * (비동기 색인 작업의 PENDING/PROCESSING 상태는 다음 작업 단위에서 다룬다).
     */
    @Insert("INSERT INTO tb_document (user_id, knowledge_base_id, title, source, index_status) "
            + "VALUES (#{userId}, #{knowledgeBaseId}, #{title}, #{source}, 'INDEXED')")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(DocumentInsertParameter parameter);
}
