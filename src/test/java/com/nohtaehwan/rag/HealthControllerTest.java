package com.nohtaehwan.rag;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nohtaehwan.rag.health.mapper.HealthMapper;

@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthMapper healthMapper;

    @Test
    void health_DB가_정상이면_UP을_반환한다() throws Exception {
        when(healthMapper.selectOne()).thenReturn(1);

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));
    }

    @Test
    void health_DB_접근_실패시_예외를_삼키지_않고_그대로_전파한다() {
        // HealthController는 별도 예외 처리를 하지 않는다. 실제 운영 환경(내장 Tomcat)에서는 이 예외가
        // 컨테이너의 기본 에러 처리로 이어져 500 응답이 되지만, @WebMvcTest 슬라이스에는 전역 에러
        // 핸들러가 없어 MockMvc.perform()까지 예외가 그대로 전파된다 — 이 테스트는 "삼켜지지 않는다"만
        // 확인한다. MyBatis 예외도 Spring의 PersistenceExceptionTranslationPostProcessor를 통해
        // DataAccessException 계열로 변환되므로, 이전 JdbcTemplate 시절과 동일한 예외 계층이다.
        when(healthMapper.selectOne())
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThatThrownBy(() -> mockMvc.perform(get("/health")))
                .hasCauseInstanceOf(DataAccessResourceFailureException.class);
    }
}
