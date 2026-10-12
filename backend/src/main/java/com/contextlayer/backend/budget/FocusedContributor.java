package com.contextlayer.backend.budget;

import com.contextlayer.backend.budget.BudgetModel.OptionContext;
import com.contextlayer.backend.budget.BudgetModel.OptionDraft;
import com.contextlayer.backend.compress.FocusedView;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FocusedContributor implements ContextOptionContributor {

	@Override
	public List<OptionDraft> contribute(OptionContext ctx) {
		// Only for files that actually matched the task (they have chunk hits), to avoid pointless work.
		if (ctx.content() == null || ctx.chunks().isEmpty() || ctx.keywords().isEmpty()) {
			return List.of();
		}
		String view = FocusedView.build(ctx.content(), ctx.symbols(), ctx.keywords());
		if (view == null) {
			return List.of();
		}
		return List.of(
			new OptionDraft(
				DetailLevel.FOCUSED,
				CodeBlocks.fence(
					ctx.path() + " (focused view; ⋯ marks omitted code)",
					CodeBlocks.languageTag(ctx.path()),
					view
				)
			)
		);
	}
}
