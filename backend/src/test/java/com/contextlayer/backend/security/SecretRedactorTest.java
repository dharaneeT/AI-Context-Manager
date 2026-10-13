package com.contextlayer.backend.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SecretRedactorTest {

	@Test
	void masksPropertyValues() {
		var r = SecretRedactor.redact("spring.datasource.password=1234567890\nserver.port=8080\n");

		assertThat(r.text()).contains("spring.datasource.password=[REDACTED]").contains("server.port=8080");
		assertThat(r.redactions()).isEqualTo(1);
	}

	@Test
	void masksQuotedLiteralsButKeepsTheVariableName() {
		var r = SecretRedactor.redact("String apiKey = \"abcd1234efgh\";");

		assertThat(r.text()).isEqualTo("String apiKey = \"[REDACTED]\";");
	}

	@Test
	void masksKnownTokenShapes() {
		String text =
			"aws AKIAABCDEFGHIJKLMNOP and gh ghp_" +
			"a".repeat(36) +
			"\n-----BEGIN RSA PRIVATE KEY-----\nMIIE...\n-----END RSA PRIVATE KEY-----";

		var r = SecretRedactor.redact(text);

		assertThat(r.text()).doesNotContain("AKIAABCDEFGHIJKLMNOP").doesNotContain("ghp_").doesNotContain("MIIE");
		assertThat(r.redactions()).isEqualTo(3);
	}

	@Test
	void leavesOrdinaryCodeAlone() {
		String code =
			"int timeoutSeconds = 30;\nString name = \"hello world\";\n// check the password length\n" +
			"private String secret = compute();\n";

		var r = SecretRedactor.redact(code);

		assertThat(r.text()).isEqualTo(code);
		assertThat(r.redactions()).isZero();
	}
}
