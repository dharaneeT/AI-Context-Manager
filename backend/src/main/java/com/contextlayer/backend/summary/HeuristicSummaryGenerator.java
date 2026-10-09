package com.contextlayer.backend.summary;

import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.symbols.CodeSymbol;
import com.contextlayer.backend.symbols.SymbolKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class HeuristicSummaryGenerator implements SummaryGenerator {

	private static final Pattern MAPPING = Pattern.compile(
		"@(Get|Post|Put|Delete|Patch|Request)Mapping(?:\\(\\s*(?:(?:value|path)\\s*=\\s*)?\"([^\"]*)\")?"
	);
	private static final Pattern PROPERTY_KEY = Pattern.compile("^\\s*([\\w.\\-\\[\\]]+)\\s*[=:]", Pattern.MULTILINE);
	private static final Pattern YAML_KEY = Pattern.compile("^([A-Za-z_][\\w\\-]*):", Pattern.MULTILINE);
	private static final Pattern MD_HEADING = Pattern.compile("^#{1,3}\\s+(.+)$", Pattern.MULTILINE);
	private static final Pattern XML_ARTIFACT = Pattern.compile("<artifactId>([^<]+)</artifactId>");

	@Override
	public String name() {
		return "heuristic";
	}

	@Override
	public String summarize(SummaryInput in) {
		String fileName = in.path().substring(in.path().lastIndexOf('/') + 1);
		StringBuilder sb = new StringBuilder(fileName)
			.append(" is a ")
			.append(in.language())
			.append(" file of ")
			.append(in.lineCount())
			.append(" lines.");

		if (!in.symbols().isEmpty()) {
			sb.append(describeSymbols(in.symbols()));
		} else {
			sb.append(describeByLanguage(in));
		}
		return Text.truncate(sb.toString(), 1500);
	}

	private static String describeSymbols(List<CodeSymbol> symbols) {
		StringBuilder sb = new StringBuilder();

		List<String> types = new ArrayList<>();
		for (CodeSymbol s : symbols) {
			if (s.getKind().isType() && !s.getQualifiedName().contains(".")) { // top-level types only
				StringBuilder t = new StringBuilder(s.getKind().name().toLowerCase(Locale.ROOT))
					.append(' ')
					.append(s.getName());
				if (s.getAnnotations() != null) {
					t.append(" (").append(firstAnnotations(s.getAnnotations())).append(')');
				}
				if (s.getDoc() != null) {
					t.append(" — ").append(s.getDoc());
				}
				types.add(t.toString());
			}
		}
		if (!types.isEmpty()) {
			sb.append(" Declares ").append(String.join("; ", types)).append('.');
		}

		List<String> endpoints = endpoints(symbols);
		if (!endpoints.isEmpty()) {
			sb.append(" Endpoints: ").append(String.join(", ", endpoints)).append('.');
		}

		Set<String> members = symbols
			.stream()
			.filter(s -> s.getKind() == SymbolKind.METHOD || s.getKind() == SymbolKind.FUNCTION)
			.map(CodeSymbol::getName)
			.collect(Collectors.toCollection(LinkedHashSet::new));
		if (!members.isEmpty()) {
			List<String> shown = members.stream().limit(12).toList();
			sb.append(" Members: ").append(String.join(", ", shown));
			if (members.size() > shown.size()) {
				sb.append(" (+").append(members.size() - shown.size()).append(" more)");
			}
			sb.append('.');
		}
		return sb.toString();
	}

	/** "GET /api/projects/{id}": class-level @RequestMapping path + method-level mapping. */
	private static List<String> endpoints(List<CodeSymbol> symbols) {
		List<String> result = new ArrayList<>();
		String prefix = "";
		for (CodeSymbol s : symbols) { // ordered by start line: a class precedes its methods
			if (s.getAnnotations() == null) {
				continue;
			}
			Matcher m = MAPPING.matcher(s.getAnnotations());
			if (!m.find()) {
				continue;
			}
			String path = m.group(2) == null ? "" : m.group(2);
			if (s.getKind().isType()) {
				prefix = path;
			} else if (s.getKind() == SymbolKind.METHOD) {
				String verb = m.group(1).equals("Request") ? "ANY" : m.group(1).toUpperCase(Locale.ROOT);
				result.add(verb + " " + prefix + path);
			}
		}
		return result;
	}

	private static String firstAnnotations(String annotations) {
		String[] parts = annotations.split("(?=@)");
		return String.join(
			" ",
			java.util.Arrays.stream(parts).map(String::strip).filter(p -> !p.isEmpty()).limit(2).toList()
		);
	}

	private static String describeByLanguage(SummaryInput in) {
		String content = in.content() == null ? "" : in.content();
		switch (in.language()) {
			case "markdown":
				{
					Matcher h = MD_HEADING.matcher(content);
					String heading = h.find() ? h.group(1).strip() : null;
					String firstLine = content
						.lines()
						.map(String::strip)
						.filter(l -> !l.isEmpty() && !l.startsWith("#"))
						.findFirst()
						.orElse(null);
					return (
						(heading != null ? " Document titled \"" + heading + "\"." : "") +
						(firstLine != null ? " Starts: " + Text.truncate(firstLine, 160) : "")
					);
				}
			case "properties":
				{
					List<String> keys = keys(PROPERTY_KEY, content); // KEY NAMES ONLY: values may be secrets
					return keys.isEmpty() ? "" : " Configuration keys include: " + String.join(", ", keys) + ".";
				}
			case "yaml":
				{
					List<String> keys = keys(YAML_KEY, content);
					return keys.isEmpty() ? "" : " Top-level keys: " + String.join(", ", keys) + ".";
				}
			case "xml":
				{
					Matcher a = XML_ARTIFACT.matcher(content);
					return a.find() ? " Build/config file for artifact " + a.group(1) + "." : "";
				}
			default:
				{
					String first = content
						.lines()
						.map(String::strip)
						.filter(l ->
							!l.isEmpty() &&
							!l.startsWith("//") &&
							!l.startsWith("#") &&
							!l.startsWith("/*") &&
							!l.startsWith("*")
						)
						.findFirst()
						.orElse(null);
					return first == null ? "" : " Begins with: " + Text.truncate(first, 120);
				}
		}
	}

	private static List<String> keys(Pattern pattern, String content) {
		Set<String> keys = new LinkedHashSet<>();
		Matcher m = pattern.matcher(content);
		while (m.find() && keys.size() < 8) {
			keys.add(m.group(1));
		}
		return List.copyOf(keys);
	}
}
