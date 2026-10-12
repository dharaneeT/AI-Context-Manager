package com.contextlayer.backend.budget;

import com.contextlayer.backend.budget.BudgetModel.OptionContext;
import com.contextlayer.backend.budget.BudgetModel.OptionDraft;
import java.util.List;

public interface ContextOptionContributor {
	List<OptionDraft> contribute(OptionContext ctx);
}
