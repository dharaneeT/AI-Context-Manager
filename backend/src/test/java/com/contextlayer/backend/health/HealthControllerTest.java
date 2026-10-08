package com.contextlayer.backend.health;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HealthControllerTest {

	@Autowired
	HealthController controller;

	@Test
	void reportsUpWhenDatabaseIsReachable() {
		var response = controller.health();
		assertThat(response.status()).isEqualTo("UP");
		assertThat(response.database()).isTrue();
	}
}
