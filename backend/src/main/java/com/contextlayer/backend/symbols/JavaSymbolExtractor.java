package com.contextlayer.backend.symbols;

import com.contextlayer.backend.common.Text;
import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class JavaSymbolExtractor implements SymbolExtractor {

	private final JavaParser parser = new JavaParser(
		new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
	);

	@Override
	public Set<String> languages() {
		return Set.of("java");
	}

	@Override
	public List<ExtractedSymbol> extract(String content) {
		ParseResult<CompilationUnit> result = parser.parse(content);
		if (result.getResult().isEmpty()) {
			return List.of(); // unparseable file: no symbols, the line-based chunker still works
		}
		CompilationUnit unit = result.getResult().get();
		List<ExtractedSymbol> out = new ArrayList<>();

		for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
			String typeName = qualifiedName(type);
			out.add(symbol(kindOf(type), type.getNameAsString(), typeName, typeSignature(type), type));

			for (MethodDeclaration method : type.getMethods()) {
				out.add(
					symbol(
						SymbolKind.METHOD,
						method.getNameAsString(),
						typeName + "." + method.getNameAsString(),
						method.getDeclarationAsString(true, false, true),
						method
					)
				);
			}
			for (ConstructorDeclaration ctor : type.getConstructors()) {
				out.add(
					symbol(
						SymbolKind.CONSTRUCTOR,
						ctor.getNameAsString(),
						typeName + "." + ctor.getNameAsString(),
						ctor.getDeclarationAsString(true, false, true),
						ctor
					)
				);
			}
		}
		out.sort((a, b) -> Integer.compare(a.startLine(), b.startLine()));
		return out;
	}

	private static ExtractedSymbol symbol(SymbolKind kind, String name, String qualified, String signature, Node node) {
		int begin = node.getRange().map(r -> r.begin.line).orElse(1);
		int end = node.getRange().map(r -> r.end.line).orElse(begin);
		// Start at the doc comment (if any), so a chunk cut here keeps the Javadoc with its method.
		int start = node.getComment().flatMap(Node::getRange).map(r -> Math.min(r.begin.line, begin)).orElse(begin);
		return new ExtractedSymbol(
			kind,
			name,
			qualified,
			Text.truncate(signature, 600),
			start,
			end,
			doc(node),
			annotations(node)
		);
	}

	private static SymbolKind kindOf(TypeDeclaration<?> type) {
		if (type instanceof ClassOrInterfaceDeclaration c) {
			return c.isInterface() ? SymbolKind.INTERFACE : SymbolKind.CLASS;
		}
		if (type instanceof EnumDeclaration) {
			return SymbolKind.ENUM;
		}
		if (type instanceof RecordDeclaration) {
			return SymbolKind.RECORD;
		}
		return SymbolKind.INTERFACE; // annotation declarations
	}

	private static String typeSignature(TypeDeclaration<?> type) {
		StringBuilder sb = new StringBuilder(kindOf(type).name().toLowerCase())
			.append(' ')
			.append(type.getNameAsString());
		if (type instanceof ClassOrInterfaceDeclaration c) {
			if (!c.getExtendedTypes().isEmpty()) {
				sb
					.append(" extends ")
					.append(c.getExtendedTypes().stream().map(Object::toString).collect(Collectors.joining(", ")));
			}
			if (!c.getImplementedTypes().isEmpty()) {
				sb
					.append(" implements ")
					.append(c.getImplementedTypes().stream().map(Object::toString).collect(Collectors.joining(", ")));
			}
		}
		return sb.toString();
	}

	/** "Outer.Inner" for nested types. */
	private static String qualifiedName(TypeDeclaration<?> type) {
		Optional<TypeDeclaration> parent = type.findAncestor(TypeDeclaration.class);
		return parent.map(p -> qualifiedName(p) + "." + type.getNameAsString()).orElse(type.getNameAsString());
	}

	private static String doc(Node node) {
		return node
			.getComment()
			.filter(JavadocComment.class::isInstance)
			.map(JavadocComment.class::cast)
			.map(c -> {
				try {
					return c.parse().getDescription().toText();
				} catch (RuntimeException e) {
					return "";
				}
			})
			.map(Text::firstSentence)
			.orElse(null);
	}

	private static String annotations(Node node) {
		if (!(node instanceof NodeWithAnnotations<?> annotated) || annotated.getAnnotations().isEmpty()) {
			return null;
		}
		String joined = annotated
			.getAnnotations()
			.stream()
			.map(AnnotationExpr::toString)
			.collect(Collectors.joining(" "))
			.replaceAll("\\s+", " ");
		return Text.truncate(joined, 400);
	}
}
