package com.contextlayer.backend.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Integration test against the real pgvector Postgres from docker-compose.
 * Runs only when CL_PG_TEST_PASSWORD is set; override the URL with CL_PG_TEST_URL.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "CL_PG_TEST_PASSWORD", matches = ".+")
@TestPropertySource(
	properties = {
		"spring.datasource.url=${CL_PG_TEST_URL:jdbc:postgresql://localhost:5434/contextlayer_test}",
		"spring.datasource.username=postgres",
		"spring.datasource.password=${CL_PG_TEST_PASSWORD}",
		"spring.jpa.hibernate.ddl-auto=update"
	}
)
public @interface PostgresIntegrationTest {
}
