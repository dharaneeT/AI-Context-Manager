package com.contextlayer.backend.pack;

import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.pack.PackModel.Role;
import java.util.List;

public final class PackRenderer {

	public record Item(String path, Role role, String level, double score, List<String> reasons, String text) {}

	public record Input(
		String task,
		String overview,
		List<Item> items,
		List<String> omitted,
		List<String> recent,
		boolean explain
	) {}

	private PackRenderer() {}

	public static String render(Input in) {
		StringBuilder sb = new StringBuilder();
		sb
			.append("# Context pack\n\n")
			.append("**Task:** ")
			.append(in.task().strip())
			.append("\n\n")
			.append("> The files below were selected and ranked for this task. `⋯` marks code omitted to save ")
			.append("space; ask for a file or line range if you need it.\n\n")
			.append("## Project\n")
			.append(in.overview())
			.append("\n\n");

		List<Item> primary = byRole(in, Role.PRIMARY);
		if (!primary.isEmpty()) {
			sb.append("## Relevant code\n\n");
			primary.forEach(i -> item(sb, i, in.explain()));
		}
		List<Item> related = byRole(in, Role.RELATED);
		if (!related.isEmpty()) {
			sb.append("## Related files\n\n");
			related.forEach(i -> item(sb, i, in.explain()));
		}
		List<Item> alsoSee = byRole(in, Role.ALSO_SEE);
		if (!alsoSee.isEmpty()) {
			sb.append("## Also see\n");
			for (Item i : alsoSee) {
				sb.append("- ").append(i.path());
				if (in.explain() && !i.reasons().isEmpty()) {
					sb.append(" — ").append(Text.truncate(i.reasons().get(0), 120));
				}
				sb.append('\n');
			}
			sb.append('\n');
		}
		if (!in.recent().isEmpty()) {
			sb.append("## Recent activity\n");
			in.recent().forEach(line -> sb.append("- ").append(line).append('\n'));
			sb.append('\n');
		}
		if (in.explain() && !in.omitted().isEmpty()) {
			sb
				.append("## Considered but omitted for space\n")
				.append(String.join(", ", in.omitted().stream().limit(8).toList()))
				.append('\n');
		}
		return sb.toString().stripTrailing();
	}

	private static List<Item> byRole(Input in, Role role) {
		return in.items().stream().filter(i -> i.role() == role).toList();
	}

	private static void item(StringBuilder sb, Item i, boolean explain) {
		if (explain && !i.reasons().isEmpty()) {
			sb
				.append("*Why selected: ")
				.append(Text.truncate(String.join("; ", i.reasons().stream().limit(3).toList()), 300))
				.append("*\n\n");
		}
		sb.append(i.text().strip()).append("\n\n");
	}
}
