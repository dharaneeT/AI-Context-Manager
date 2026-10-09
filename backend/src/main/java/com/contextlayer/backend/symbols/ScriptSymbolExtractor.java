package com.contextlayer.backend.symbols;

import com.contextlayer.backend.common.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ScriptSymbolExtractor implements SymbolExtractor {

	private static final Pattern CLASS = Pattern.compile(
		"^\\s*(?:export\\s+)?(?:default\\s+)?(?:abstract\\s+)?class\\s+(\\w+)"
	);
	private static final Pattern FUNCTION = Pattern.compile(
		"^\\s*(?:export\\s+)?(?:default\\s+)?(?:async\\s+)?function\\s*\\*?\\s*(\\w+)\\s*\\("
	);
	private static final Pattern ARROW = Pattern.compile(
		"^\\s*(?:export\\s+)?(?:const|let|var)\\s+(\\w+)\\s*(?::[^=]+)?=\\s*(?:async\\s*)?" +
		"(?:\\([^)]*\\)|\\w+)\\s*(?::[^=]+)?=>"
	);
	private static final Pattern TYPE = Pattern.compile(
		"^\\s*(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(interface|enum|type)\\s+(\\w+)"
	);
	private static final Pattern METHOD = Pattern.compile(
		"^[ \\t]+(?:(?:public|private|protected|static|async|get|set)\\s+)*(\\w+)\\s*\\([^)]*\\)\\s*" +
		"(?::\\s*[^{]+)?\\{"
	);
	private static final Set<String> KEYWORDS = Set.of(
		"if",
		"for",
		"while",
		"switch",
		"catch",
		"function",
		"return",
		"else",
		"do",
		"with"
	);

	@Override
	public Set<String> languages() {
		return Set.of("javascript", "typescript");
	}

	@Override
	public List<ExtractedSymbol> extract(String content) {
		List<String> lines = content.lines().toList();
		List<ExtractedSymbol> out = new ArrayList<>();
		String className = null;
		int classEnd = 0;

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			int lineNo = i + 1;
			if (className != null && lineNo > classEnd) {
				className = null;
			}
			Matcher m;

			if ((m = CLASS.matcher(line)).find()) {
				classEnd = BraceBlock.endLine(lines, i, 2);
				className = m.group(1);
				out.add(build(SymbolKind.CLASS, m.group(1), m.group(1), lines, i, classEnd));
			} else if ((m = FUNCTION.matcher(line)).find()) {
				out.add(build(SymbolKind.FUNCTION, m.group(1), m.group(1), lines, i, BraceBlock.endLine(lines, i, 2)));
			} else if ((m = ARROW.matcher(line)).find()) {
				out.add(build(SymbolKind.FUNCTION, m.group(1), m.group(1), lines, i, BraceBlock.endLine(lines, i, 0))); // brace must be on the same line, else a one-liner
			} else if ((m = TYPE.matcher(line)).find()) {
				SymbolKind kind = m.group(1).equals("enum")
					? SymbolKind.ENUM
					: m.group(1).equals("type") ? SymbolKind.TYPE : SymbolKind.INTERFACE;
				out.add(build(kind, m.group(2), m.group(2), lines, i, BraceBlock.endLine(lines, i, 0)));
			} else if (className != null && (m = METHOD.matcher(line)).find() && !KEYWORDS.contains(m.group(1))) {
				out.add(
					build(
						SymbolKind.METHOD,
						m.group(1),
						className + "." + m.group(1),
						lines,
						i,
						BraceBlock.endLine(lines, i, 0)
					)
				);
			}
		}
		return out;
	}

	private static ExtractedSymbol build(
		SymbolKind kind,
		String name,
		String qualified,
		List<String> lines,
		int idx,
		int endLine
	) {
		return new ExtractedSymbol(
			kind,
			name,
			qualified,
			Text.truncate(lines.get(idx).strip(), 300),
			idx + 1,
			Math.max(endLine, idx + 1),
			jsDocAbove(lines, idx),
			null
		);
	}

	/** First sentence of a block comment ending on the line above the declaration. */
	private static String jsDocAbove(List<String> lines, int idx) {
		int end = idx - 1;
		if (end < 0 || !lines.get(end).strip().endsWith("*/")) {
			return null;
		}
		int begin = end;
		while (begin >= 0 && !lines.get(begin).contains("/**")) {
			begin--;
		}
		if (begin < 0) {
			return null;
		}
		StringBuilder sb = new StringBuilder();
		for (int k = begin; k <= end; k++) {
			sb.append(lines.get(k).replaceFirst("^\\s*(/\\*\\*|\\*)", "").replace("*/", "").strip()).append(' ');
		}
		return Text.firstSentence(sb.toString());
	}
}
