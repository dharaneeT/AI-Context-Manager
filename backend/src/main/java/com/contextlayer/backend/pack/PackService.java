package com.contextlayer.backend.pack;

import com.contextlayer.backend.budget.BudgetPlan;
import com.contextlayer.backend.budget.BudgetPlan.PlanItem;
import com.contextlayer.backend.budget.BudgetService;
import com.contextlayer.backend.budget.BudgetService.Prepared;
import com.contextlayer.backend.budget.TokenCounter;
import com.contextlayer.backend.common.BadRequestException;
import com.contextlayer.backend.common.Text;
import com.contextlayer.backend.git.GitException;
import com.contextlayer.backend.git.GitFileStats;
import com.contextlayer.backend.git.GitFileStatsRepository;
import com.contextlayer.backend.git.GitIntelligenceService;
import com.contextlayer.backend.git.GitStatusParser.WorkingChange;
import com.contextlayer.backend.pack.PackModel.ContextPack;
import com.contextlayer.backend.pack.PackModel.PackItem;
import com.contextlayer.backend.pack.PackModel.PackRequest;
import com.contextlayer.backend.pack.PackModel.PackStats;
import com.contextlayer.backend.pack.PackModel.Role;
import com.contextlayer.backend.security.SecretRedactor;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PackService {

	private static final int MAX_ATTEMPTS = 4;
	private static final int RECENT_DAYS = 90;

	private final BudgetService budgetService;
	private final TokenCounter tokens;
	private final GitFileStatsRepository gitStatsRepository;
	private final GitIntelligenceService gitService;

	public ContextPack build(Long projectId, PackRequest request) {
		return build(
			projectId,
			request.task(),
			request.maxTokens(),
			request.explainOrDefault(),
			request.recentOrDefault()
		);
	}

	public ContextPack build(
		Long projectId,
		String task,
		Integer requestedTokens,
		boolean explain,
		boolean recentActivity
	) {
		Prepared prepared = budgetService.prepare(projectId, task); // ranking etc., once
		int max = budgetService.clampBudget(requestedTokens);

		Map<String, GitFileStats> stats = recentActivity ? loadStats(projectId) : Map.of();
		Set<String> working = recentActivity ? workingPaths(projectId) : Set.of();

		List<String> notes = new ArrayList<>();
		boolean withExplain = explain;
		boolean withRecent = recentActivity;
		int extra = 0;

		BudgetPlan plan = null;
		String markdown = null;
		int count = 0;
		int target = 0;
		int redactions = 0;

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			BudgetPlan candidate;
			try {
				candidate = budgetService.allocate(prepared, task, max, extra);
			} catch (BadRequestException e) {
				if (plan == null) {
					throw e; // the budget was unusable from the start
				}
				notes.add("Could not shrink the pack further: " + e.getMessage());
				break;
			}
			List<PackRenderer.Item> renderItems = candidate
				.included()
				.stream()
				.map(i ->
					new PackRenderer.Item(i.path(), Role.of(i.level()), i.level(), i.score(), i.reasons(), i.text())
				)
				.toList();
			List<String> recent = withRecent ? recentLines(candidate.included(), stats, working) : List.of();

			String raw = PackRenderer.render(
				new PackRenderer.Input(
					task,
					prepared.overview(),
					renderItems,
					candidate.notIncluded(),
					recent,
					withExplain
				)
			);
			SecretRedactor.Result redacted = SecretRedactor.redact(raw);

			plan = candidate;
			markdown = redacted.text();
			redactions = redacted.redactions();
			count = tokens.count(markdown); // exact count of what will be sent
			target = candidate.maxTokens() - candidate.safetyMarginTokens();

			if (count <= target) {
				break;
			}
			if (attempt == MAX_ATTEMPTS - 1) {
				if (withExplain || withRecent) {
					notes.add("The rationale and recent-activity sections were dropped to fit the budget.");
				}
				withExplain = false; // last resort before giving up
				withRecent = false;
			} else {
				extra += (count - target) + 25; // reserve what we overshot, plus a little
			}
		}

		if (!prepared.ranking().semanticUsed() && prepared.ranking().semanticNote() != null) {
			notes.add("Keyword-only ranking: " + prepared.ranking().semanticNote());
		}
		if (redactions > 0) {
			notes.add(redactions + " potential secret value(s) were redacted from the pack.");
		}
		if (count > target) {
			notes.add(
				"The pack is " +
				(count - target) +
				" tokens over the target even after trimming. " +
				"Increase maxTokens."
			);
		}

		List<PackItem> items = plan
			.included()
			.stream()
			.map(i -> new PackItem(i.path(), Role.of(i.level()), i.level(), i.tokens(), i.score(), i.reasons()))
			.toList();
		PackStats packStats = new PackStats(
			plan.maxTokens(),
			target,
			count,
			count <= target,
			plan.projectTokens(),
			reduction(count, plan.projectTokens()),
			plan.fullFileTokens(),
			reduction(plan.itemTokens(), plan.fullFileTokens())
		);

		return new ContextPack(
			task,
			Instant.now(),
			plan.semanticUsed(),
			packStats,
			items,
			plan.notIncluded(),
			notes,
			markdown
		);
	}

	private Map<String, GitFileStats> loadStats(Long projectId) {
		return gitStatsRepository
			.findByProjectId(projectId)
			.stream()
			.collect(Collectors.toMap(GitFileStats::getRelativePath, s -> s, (a, b) -> a));
	}

	private Set<String> workingPaths(Long projectId) {
		try {
			return gitService.workingChanges(projectId).stream().map(WorkingChange::path).collect(Collectors.toSet());
		} catch (BadRequestException | GitException e) {
			return Set.of(); // not a git repo
		}
	}

	/** Uncommitted work first, then recent commits. Only for files the pack actually shows in some detail. */
	private static List<String> recentLines(
		List<PlanItem> included,
		Map<String, GitFileStats> stats,
		Set<String> working
	) {
		Instant now = Instant.now();
		record Line(Instant when, String text) {}
		List<Line> lines = new ArrayList<>();
		for (PlanItem item : included) {
			if (Role.of(item.level()) == Role.ALSO_SEE) {
				continue;
			}
			if (working.contains(item.path())) {
				lines.add(new Line(Instant.MAX, item.path() + " — has uncommitted changes"));
				continue;
			}
			GitFileStats s = stats.get(item.path());
			if (
				s == null ||
				s.getLastCommitAt() == null ||
				Duration.between(s.getLastCommitAt(), now).toDays() > RECENT_DAYS
			) {
				continue;
			}
			lines.add(
				new Line(
					s.getLastCommitAt(),
					item.path() +
					" — last changed " +
					Duration.between(s.getLastCommitAt(), now).toDays() +
					" day(s) ago by " +
					s.getLastAuthor() +
					": \"" +
					Text.truncate(s.getLastSubject(), 100) +
					"\""
				)
			);
		}
		return lines.stream().sorted(Comparator.comparing(Line::when).reversed()).limit(6).map(Line::text).toList();
	}

	private static double reduction(long used, long original) {
		return original <= 0 ? 0 : Math.round(1000.0 * (1.0 - (double) used / original)) / 10.0;
	}
}
