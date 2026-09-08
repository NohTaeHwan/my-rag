package com.nohtaehwan.rag;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nohtaehwan.rag.health.mapper.HealthMapper;

import io.swagger.v3.oas.annotations.Hidden;

/**
 * 운영 상태 확인용 헬스체크. 비즈니스 API가 아니므로 Swagger 문서에서 제외한다({@link Hidden}).
 */
@Hidden
@RestController
public class HealthController {

    private final HealthMapper healthMapper;

    public HealthController(HealthMapper healthMapper) {
        this.healthMapper = healthMapper;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        healthMapper.selectOne();
        return Map.of("status", "UP", "database", "UP");
    }
}
