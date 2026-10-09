package com.contextlayer.backend.symbols;

import com.contextlayer.backend.common.Text;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class PythonSymbolExtractor implements SymbolExtractor {

	private static final Pattern DEF = Pattern.compile("^(\\s*)(?:async\\s+)?def\\s+(\\w+)\\s*\\(");
	private static final Pattern CLASS = Pattern.compile("^(\\s*)class\\s+(\\w+)");
	private static final Pattern DOCSTRING = Pattern.compile("^[rRuU]?(?:\"\"\"|''')(.*)$");

	private record Scope(int indent, String name, boolean isClass) {}

	@Override
	public Set<String> languages() {
		return Set.of("python");
	}

	@Override
	public List<ExtractedSymbol> extract(String content) {
		List<String> lines = content.lines().toList();
		List<ExtractedSymbol> out = new ArrayList<>();
		Deque<Scope> scopes = new ArrayDeque<>();

		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			Matcher def = DEF.matcher(line);
			Matcher cls = CLASS.matcher(line);
			boolean isDef = def.find();
			boolean isClass = !isDef && cls.find();
			if (!isDef && !isClass) {
				continue;
			}
			Matcher m = isDef ? def : cls;
			int indent = indentOf(line);
			String name = m.group(2);

			while (!scopes.isEmpty() && scopes.peek().indent() >= indent) {
				scopes.pop(); // we left those scopes
			}
			String parentPath = qualifier(scopes);
			String qualified = parentPath.isEmpty() ? name : parentPath + "." + name;
			boolean inClass = !scopes.isEmpty() && scopes.peek().isClass();

			int start = i;
			while (start > 0 && lines.get(start - 1).strip().startsWith("@")) {
				start--; // include decorators
			}
			int end = blockEnd(lines, i, indent);
			SymbolKind kind = isClass ? SymbolKind.CLASS : inClass ? SymbolKind.METHOD : SymbolKind.FUNCTION;
			String decorators = start == i
				? null
				: Text.truncate(String.join(" ", lines.subList(start, i).stream().map(String::strip).toList()), 400);

			out.add(
				new ExtractedSymbol(
					kind,
					name,
					qualified,
					Text.truncate(line.strip(), 300),
					start + 1,
					end + 1,
					docstring(lines, i, end),
					decorators
				)
			);
			scopes.push(new Scope(indent, name, isClass));
		}
		return out;
	}

	private static String qualifier(Deque<Scope> scopes) {
		List<String> names = new ArrayList<>();
		scopes.descendingIterator().forEachRemaining(s -> names.add(s.name()));
		return String.join(".", names);
	}

	/** Last 0-based line of the block: the final non-blank line that is indented deeper than the header. */
	private static int blockEnd(List<String> lines, int header, int indent) {
		int end = header;
		for (int j = header + 1; j < lines.size(); j++) {
			String l = lines.get(j);
			if (l.isBlank()) {
				continue;
			}
			if (indentOf(l) <= indent) {
				break;
			}
			end = j;
		}
		return end;
	}

	private static String docstring(List<String> lines, int header, int end) {
		if (!lines.get(header).stripTrailing().endsWith(":")) {
			return null; // multi-line signature: skip, keep it simple
		}
		for (int j = header + 1; j <= end && j < lines.size(); j++) {
			String l = lines.get(j).strip();
			if (l.isEmpty()) {
				continue;
			}
			Matcher m = DOCSTRING.matcher(l);
			return m.find() ? Text.firstSentence(m.group(1).replace("\"\"\"", "").replace("'''", "")) : null;
		}
		return null;
	}

	private static int indentOf(String line) {
		int n = 0;
		for (char c : line.toCharArray()) {
			if (c == ' ') {
				n++;
			} else if (c == '\t') {
				n += 4;
			} else {
				break;
			}
		}
		return n;
	}
}
