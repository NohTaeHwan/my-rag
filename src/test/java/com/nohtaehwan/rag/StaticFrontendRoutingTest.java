package com.nohtaehwan.rag;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.handler;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nohtaehwan.rag.answer.AnswerController;
import com.nohtaehwan.rag.health.mapper.HealthMapper;

/**
 * 프런트엔드 정적 파일({@code classpath:/static/index.html})이 {@code /}에서 제공되고, 기존
 * {@code /api/answers}·{@code /health}·Swagger 경로가 정적 리소스에 가려지지 않는지 확인한다.
 * 실제 화면 대신 {@code src/test/resources/static/index.html} fixture를 쓰며, DB·외부 API에 접근하는
 * 요청은 보내지 않는다({@code /api/answers}는 검증 실패 400 경로, {@code /health}의 DB 조회는 mock).
 * <p>
 * 라우팅만 검증한다. 실제 배포 번들({@code frontend/dist}의 index.html·hashed JS/CSS)이 JAR에 들어갔는지는
 * {@code build.gradle}의 {@code frontendBuild}(소스 해시 입력) → {@code bootJar} 연결과 {@code bootJar} doLast 참조 검사가 맡는다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaticFrontendRoutingTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private HealthMapper healthMapper;

    @Test
    void 루트_요청은_정적_index_html로_전달된다() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
    }

    @Test
    void index_html은_정적_리소스로_제공된다() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("static-frontend-fixture")));
    }

    @Test
    void 답변_API는_정적_리소스가_아니라_Controller가_처리한다() throws Exception {
        mockMvc.perform(post("/api/answers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(handler().handlerType(AnswerController.class));
    }

    @Test
    void health는_정적_리소스가_아니라_HealthController가_처리한다() throws Exception {
        when(healthMapper.selectOne()).thenReturn(1);

        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(handler().handlerType(HealthController.class));
    }

    @Test
    void swagger_UI_경로는_정적_리소스에_가려지지_않는다() throws Exception {
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
    }
}
