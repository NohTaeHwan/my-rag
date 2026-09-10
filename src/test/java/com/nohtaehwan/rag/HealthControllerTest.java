package com.nohtaehwan.rag;

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
    void health_DB_접근_실패시_503과_DOWN_상태를_반환한다() throws Exception {
        // MyBatis 예외도 Spring의 PersistenceExceptionTranslationPostProcessor를 통해
        // DataAccessException 계열로 변환된다. HealthController는 이걸 잡아서 sanitize된
        // DOWN 상태로 응답한다 — 배포 스크립트(deploy.sh)의 `curl -f` 헬스체크가 정상적으로
        // 실패를 감지하려면 2xx가 아닌 상태 코드가 필요하다.
        when(healthMapper.selectOne())
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        mockMvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").value("DOWN"));
    }
}
