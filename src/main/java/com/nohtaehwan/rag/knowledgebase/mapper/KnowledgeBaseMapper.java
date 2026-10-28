package com.nohtaehwan.rag.knowledgebase.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * tb_knowledge_base에 대한 MyBatis mapper. SQL은 annotation에 직접 작성한다(XML 없음).
 */
@Mapper
public interface KnowledgeBaseMapper {

    /**
     * Knowledge Base가 존재하고 주어진 사용자 소유인지 확인한다. 없는 KB와 다른 사용자 소유 KB를
     * 구분하지 않는다(호출자가 둘 다 같은 404로 응답해 존재 여부를 노출하지 않도록).
     *
     * @param knowledgeBaseId 확인할 Knowledge Base ID
     * @param userId          현재 사용자 ID
     * @return 해당 사용자 소유 KB가 있으면 true
     */
    @Select("SELECT EXISTS (SELECT 1 FROM tb_knowledge_base WHERE id = #{knowledgeBaseId} AND user_id = #{userId})")
    boolean existsByIdAndUserId(@Param("knowledgeBaseId") Long knowledgeBaseId, @Param("userId") Long userId);
}
