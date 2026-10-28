package com.nohtaehwan.rag.user;

import org.springframework.stereotype.Component;

/**
 * 현재 요청의 사용자 ID를 제공한다. 인증이 아직 없으므로 V3 migration이 시드한 개발용 사용자(id=1)만
 * 반환한다. 클라이언트가 보낸 userId는 신뢰하지 않으며, 인증 도입 시 이 클래스만 교체한다.
 *
 * <p>ponytail: 개발용 고정 사용자 — 실제 다중 사용자 보호가 아니다. 인증 도입 시 SecurityContext 기반으로 바꾼다.
 */
@Component
public class CurrentUserProvider {

    private static final Long DEVELOPMENT_USER_ID = 1L;

    /**
     * @return 현재 사용자 ID(현재는 항상 개발용 사용자 1)
     */
    public Long currentUserId() {
        return DEVELOPMENT_USER_ID;
    }
}
