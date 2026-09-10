package com.nohtaehwan.rag.config;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nohtaehwan.rag.HealthController;
import com.nohtaehwan.rag.health.mapper.HealthMapper;

/**
 * RequestIdFilter가 실제 Spring MVC 요청 처리 경로에 적용되는지 확인한다.
 * {@link HealthController}를 대상 컨트롤러로 사용한다 — 이 필터는 모든 경로에 적용되므로
 * 어떤 컨트롤러를 슬라이스 대상으로 삼아도 무방하며, 기존 테스트가 이미 검증된 최소 컨트롤러다.
 */
@WebMvcTest(HealthController.class)
class RequestIdFilterIntegrationTest {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthMapper healthMapper;

    @Test
    void X_Request_Id_헤더가_없으면_UUID_형식의_응답_헤더가_생성된다() throws Exception {
        when(healthMapper.selectOne()).thenReturn(1);

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, matchesPattern(UUID_PATTERN.pattern())));
    }

    @Test
    void 유효한_X_Request_Id는_동일하게_응답된다() throws Exception {
        when(healthMapper.selectOne()).thenReturn(1);

        mockMvc.perform(get("/health").header(RequestIdFilter.REQUEST_ID_HEADER, "integration-test-id"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, "integration-test-id"));
    }

    @Test
    void 무효한_X_Request_Id는_새_UUID로_대체되어_응답된다() throws Exception {
        when(healthMapper.selectOne()).thenReturn(1);

        mockMvc.perform(get("/health").header(RequestIdFilter.REQUEST_ID_HEADER, "invalid id/with bad chars"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, matchesPattern(UUID_PATTERN.pattern())));
    }

    @Test
    void 응답_헤더는_하나만_존재하고_필터가_설정한_값과_일치한다() throws Exception {
        when(healthMapper.selectOne()).thenReturn(1);

        String requestId = UUID.randomUUID().toString();
        mockMvc.perform(get("/health").header(RequestIdFilter.REQUEST_ID_HEADER, requestId))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestIdFilter.REQUEST_ID_HEADER, requestId));
    }
}
