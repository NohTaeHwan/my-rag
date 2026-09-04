package com.nohtaehwan.rag.health.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/**
 * Health check용 MyBatis mapper. SQL은 annotation에 직접 작성한다(XML 없음).
 */
@Mapper
public interface HealthMapper {

    /**
     * {@code SELECT 1}을 실행한다. DB 연결이 살아있는지만 확인하며 반환값 자체는 쓰지 않는다.
     */
    @Select("SELECT 1")
    Integer selectOne();
}
