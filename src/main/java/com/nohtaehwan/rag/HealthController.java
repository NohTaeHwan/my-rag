package com.nohtaehwan.rag;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nohtaehwan.rag.health.mapper.HealthMapper;

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
