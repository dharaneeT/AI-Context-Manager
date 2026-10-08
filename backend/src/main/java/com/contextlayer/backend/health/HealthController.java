package com.contextlayer.backend.health;

import java.sql.Connection;
import java.time.Instant;
import javax.sql.DataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api")
public class HealthController {

    private final DataSource dataSource;
    private final String applicationName;

    public HealthController(DataSource dataSource,
                            @Value("${spring.application.name}") String applicationName) {
        this.dataSource = dataSource;
        this.applicationName = applicationName;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        boolean dbUp = isDatabaseUp();
        return new HealthResponse(dbUp ? "UP" : "DEGRADED", applicationName, dbUp, Instant.now().toString());
    }

    private boolean isDatabaseUp() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (Exception e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }

    public record HealthResponse(String status, String application, boolean database, String time) {
    }
}