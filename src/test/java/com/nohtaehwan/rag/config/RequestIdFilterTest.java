package com.nohtaehwan.rag.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        // 각 테스트가 MDC 상태를 오염시켜 다른 테스트에 영향을 주지 않도록 정리한다.
        MDC.clear();
    }

    /** chain.doFilter() 호출 시점의 MDC 값을 캡처하도록 목을 구성한다. */
    private String[] captureMdcDuringChain(FilterChain chain) throws Exception {
        String[] captured = new String[1];
        doAnswer(invocation -> {
            captured[0] = MDC.get(RequestIdFilter.MDC_KEY);
            return null;
        }).when(chain).doFilter(any(), any());
        return captured;
    }

    @Test
    void 유효한_기존_ID가_있으면_그대로_사용하고_체인_실행중_MDC에도_동일하게_반영된다() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).thenReturn("client-supplied-id");
        String[] mdcDuringChain = captureMdcDuringChain(chain);

        filter.doFilter(request, response, chain);

        verify(response).setHeader(RequestIdFilter.REQUEST_ID_HEADER, "client-supplied-id");
        assertThat(mdcDuringChain[0]).isEqualTo("client-supplied-id");
    }

    @Test
    void 헤더가_없으면_UUID를_발급하고_체인_실행중_MDC에_반영하며_처리후_MDC를_정리한다() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).thenReturn(null);
        String[] mdcDuringChain = captureMdcDuringChain(chain);

        filter.doFilter(request, response, chain);

        verify(response).setHeader(eq(RequestIdFilter.REQUEST_ID_HEADER), argThat(this::isUuid));
        assertThat(mdcDuringChain[0]).isNotBlank().matches(this::isUuid);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void blank_헤더는_무시하고_새_UUID를_발급한다() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).thenReturn("   ");

        filter.doFilter(request, response, chain);

        verify(response).setHeader(eq(RequestIdFilter.REQUEST_ID_HEADER),
                argThat(id -> !id.equals("   ") && isUuid(id)));
    }

    @Test
    void 길이가_128자를_초과하면_거부하고_새_UUID를_발급하며_원본은_MDC에_남지_않는다() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        String tooLong = "a".repeat(129);
        when(request.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).thenReturn(tooLong);
        String[] mdcDuringChain = captureMdcDuringChain(chain);

        filter.doFilter(request, response, chain);

        verify(response).setHeader(eq(RequestIdFilter.REQUEST_ID_HEADER), argThat(id -> !id.equals(tooLong)));
        assertThat(mdcDuringChain[0]).isNotEqualTo(tooLong).matches(this::isUuid);
    }

    @ParameterizedTest
    @ValueSource(strings = {"request id", "request/id", "request.id", "request-id\nforged-log-line"})
    void 허용되지_않은_문자가_있으면_원본을_버리고_새_UUID를_발급한다(String malicious) throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).thenReturn(malicious);
        String[] mdcDuringChain = captureMdcDuringChain(chain);

        filter.doFilter(request, response, chain);

        verify(response).setHeader(eq(RequestIdFilter.REQUEST_ID_HEADER), argThat(id -> !id.equals(malicious) && isUuid(id)));
        assertThat(mdcDuringChain[0]).isNotEqualTo(malicious).matches(this::isUuid);
    }

    @Test
    void 체인_실행_중_예외가_발생해도_예외는_전파되고_MDC는_정리된다() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).thenReturn(null);
        doThrow(new RuntimeException("체인 실패")).when(chain).doFilter(request, response);

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(RuntimeException.class);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 응답_헤더_설정중_예외가_발생해도_예외는_전파되고_MDC는_정리된다() throws Exception {
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).thenReturn(null);
        doThrow(new RuntimeException("응답 헤더 설정 실패"))
                .when(response).setHeader(eq(RequestIdFilter.REQUEST_ID_HEADER), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .isInstanceOf(RuntimeException.class);
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void 필터_진입_전에_MDC에_값이_있었다면_체인_종료_후_그_값으로_복원한다() throws Exception {
        MDC.put(RequestIdFilter.MDC_KEY, "previous-request-id");
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);
        when(request.getHeader(RequestIdFilter.REQUEST_ID_HEADER)).thenReturn(null);
        String[] mdcDuringChain = captureMdcDuringChain(chain);

        filter.doFilter(request, response, chain);

        assertThat(mdcDuringChain[0]).isNotEqualTo("previous-request-id");
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isEqualTo("previous-request-id");
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
