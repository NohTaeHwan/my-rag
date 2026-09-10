package com.nohtaehwan.rag;

import java.util.Map;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nohtaehwan.rag.health.mapper.HealthMapper;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.extern.slf4j.Slf4j;

/**
 * 운영 상태 확인용 헬스체크. 비즈니스 API가 아니므로 Swagger 문서에서 제외한다({@link Hidden}).
 */
@Slf4j
@Hidden
@RestController
public class HealthController {

    private final HealthMapper healthMapper;

    public HealthController(HealthMapper healthMapper) {
        this.healthMapper = healthMapper;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        try {
            healthMapper.selectOne();
        } catch (DataAccessException e) {
            log.warn("DB 헬스체크 실패", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "database", "DOWN"));
        }
        return ResponseEntity.ok(Map.of("status", "UP", "database", "UP"));
    }
}
