package com.contextlayer.backend.budget;

import com.contextlayer.backend.budget.BudgetModel.OptionContext;
import com.contextlayer.backend.budget.BudgetModel.OptionDraft;
import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.config.BudgetProperties;
import com.contextlayer.backend.symbols.CodeSymbol;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

public final class StandardContributors {

	private StandardContributors() {}

	@Component
	public static class PathOption implements ContextOptionContributor {

		@Override
		public List<OptionDraft> contribute(OptionContext ctx) {
			return List.of(new OptionDraft(DetailLevel.PATH_ONLY, "- " + ctx.path()));
		}
	}

	@Component
	public static class SummaryOption implements ContextOptionContributor {

		@Override
		public List<OptionDraft> contribute(OptionContext ctx) {
			return ctx.summary() == null
				? List.of()
				: List.of(new OptionDraft(DetailLevel.SUMMARY, "**" + ctx.path() + "** — " + ctx.summary()));
		}
	}

	@Component
	public static class OutlineOption implements ContextOptionContributor {

		@Override
		public List<OptionDraft> contribute(OptionContext ctx) {
			if (ctx.symbols().isEmpty()) {
				return List.of();
			}
			String signatures = ctx
				.symbols()
				.stream()
				.limit(40)
				.map((CodeSymbol s) ->
					"  L" +
					s.getStartLine() +
					"-" +
					s.getEndLine() +
					" " +
					Text.truncate(s.getSignature() == null ? s.getQualifiedName() : s.getSignature(), 160)
				)
				.collect(Collectors.joining("\n"));
			String head = "**" + ctx.path() + "**" + (ctx.summary() == null ? "" : " — " + ctx.summary());
			return List.of(new OptionDraft(DetailLevel.OUTLINE, head + "\n" + signatures));
		}
	}

	/** The top-k matching chunks, merged by line number so overlaps aren't repeated. */
	@Component
	@RequiredArgsConstructor
	public static class ChunkOption implements ContextOptionContributor {

		private final BudgetProperties props;

		@Override
		public List<OptionDraft> contribute(OptionContext ctx) {
			List<OptionDraft> out = new ArrayList<>();
			int max = Math.min(Math.min(props.maxChunksPerFile(), 3), ctx.chunks().size());
			for (int k = 1; k <= max; k++) {
				LineSet set = new LineSet();
				ctx.chunks().stream().limit(k).forEach(c -> set.addChunk(c.startLine(), c.content()));
				String ranges = set.runs().stream().map(r -> r.from() + "-" + r.to()).collect(Collectors.joining(", "));
				out.add(
					new OptionDraft(
						DetailLevel.code(k),
						CodeBlocks.fence(
							ctx.path() + " (lines " + ranges + ")",
							CodeBlocks.languageTag(ctx.path()),
							set.render()
						)
					)
				);
			}
			return out;
		}
	}
}
