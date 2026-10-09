package com.contextlayer.backend.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ImportStrategyTest {

	private static Map<String, List<String>> bySpecifier(List<ResolvedImport> imports) {
		Map<String, List<String>> map = new HashMap<>();
		imports.forEach(i -> map.put(i.specifier(), i.targets()));
		return map;
	}

	@Test
	void java() {
		var index = new ProjectIndex(
			Set.of("a/Foo.java", "b/Bar.java"),
			Map.of("com.a.Foo", "a/Foo.java", "com.b.Bar", "b/Bar.java"),
			Map.of("com.b", List.of("b/Bar.java")),
			Map.of()
		);
		String code =
			"""
                package com.a;
                import com.b.Bar;
                import java.util.List;
                import com.b.*;
                import static com.b.Bar.helper;
                """;

		var result = bySpecifier(new JavaImportStrategy().analyze("a/Foo.java", code, index));

		assertThat(result.get("com.b.Bar")).containsExactly("b/Bar.java");
		assertThat(result.get("java.util.List")).isEmpty(); // external
		assertThat(result.get("com.b.*")).containsExactly("b/Bar.java"); // wildcard -> package members
		assertThat(result.get("com.b.Bar.helper")).containsExactly("b/Bar.java"); // static member -> its class
	}

	@Test
	void script() {
		var index = new ProjectIndex(
			Set.of("src/App.jsx", "src/utils/format.js", "src/components/Button/index.jsx"),
			Map.of(),
			Map.of(),
			Map.of()
		);
		String code =
			"""
                import React from 'react';
                import { fmt } from './utils/format';
                import Button from "./components/Button";
                const x = require('./utils/format.js');
                export * from './missing';
                """;

		var result = bySpecifier(new ScriptImportStrategy().analyze("src/App.jsx", code, index));

		assertThat(result.get("react")).isEmpty();
		assertThat(result.get("./utils/format")).containsExactly("src/utils/format.js");
		assertThat(result.get("./components/Button")).containsExactly("src/components/Button/index.jsx");
		assertThat(result.get("./utils/format.js")).containsExactly("src/utils/format.js");
		assertThat(result.get("./missing")).isEmpty();
	}

	@Test
	void python() {
		var index = new ProjectIndex(
			Set.of("app/main.py", "app/utils/__init__.py", "app/utils/db.py"),
			Map.of(),
			Map.of(),
			Map.of("app.utils.db", "app/utils/db.py")
		);
		String code =
			"""
                from app.utils.db import connect
                from .utils import db
                import os
                """;

		var result = bySpecifier(new PythonImportStrategy().analyze("app/main.py", code, index));

		assertThat(result.get("app.utils.db")).containsExactly("app/utils/db.py");
		assertThat(result.get(".utils")).contains("app/utils/db.py");
		assertThat(result.get("os")).isEmpty();
	}
}
