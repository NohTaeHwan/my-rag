package com.nohtaehwan.rag.config;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청마다 고유 ID를 발급해 로그 추적에 사용한다. 클라이언트가 {@value #REQUEST_ID_HEADER} 헤더로
 * ID를 보내면 검증 후 그 값을 쓰고, 없거나 무효하면 새로 발급한다. 요청 처리 중 찍히는 모든 로그에
 * MDC로 끼워 넣고(logback-spring.xml의 {@code [%X{requestId}]} 패턴에서 참조), 응답 헤더로도
 * 그대로 돌려준다.
 *
 * <p>클라이언트가 보낸 값을 검증 없이 로그에 그대로 남기면 개행 삽입으로 로그를 위조하거나
 * (log forging) 과도하게 긴 값으로 로그를 오염시킬 수 있어, 영문/숫자/하이픈/언더스코어
 * 128자 이내로만 제한한다. 무효한 값은 원본을 버리고 새 UUID로 대체한다.
 */
@Component
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";

    private static final Pattern VALID_REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String previousRequestId = MDC.get(MDC_KEY);
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));

        try {
            MDC.put(MDC_KEY, requestId);
            response.setHeader(REQUEST_ID_HEADER, requestId);
            filterChain.doFilter(request, response);
        } finally {
            if (previousRequestId == null) {
                MDC.remove(MDC_KEY);
            } else {
                MDC.put(MDC_KEY, previousRequestId);
            }
        }
    }

    private String resolveRequestId(String candidate) {
        if (candidate != null && VALID_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }
}
