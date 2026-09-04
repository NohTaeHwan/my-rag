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
     * 주어진 source의 기존 문서를 삭제한다. tb_document_chunk는 FK ON DELETE CASCADE로 함께 삭제된다.
     *
     * @return 삭제된 행 수
     */
    @Delete("DELETE FROM tb_document WHERE source = #{source}")
    int deleteBySource(@Param("source") String source);

    /**
     * 문서를 insert하고 생성된 id를 parameter.id에 채운다.
     */
    @Insert("INSERT INTO tb_document (title, source) VALUES (#{title}, #{source})")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insert(DocumentInsertParameter parameter);
}
