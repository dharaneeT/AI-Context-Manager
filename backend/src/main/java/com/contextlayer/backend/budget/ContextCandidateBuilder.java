package com.contextlayer.backend.budget;

import com.contextlayer.backend.budget.BudgetModel.Candidate;
import com.contextlayer.backend.budget.BudgetModel.ChunkText;
import com.contextlayer.backend.budget.BudgetModel.ContextOption;
import com.contextlayer.backend.budget.BudgetModel.OptionContext;
import com.contextlayer.backend.budget.BudgetModel.OptionDraft;
import com.contextlayer.backend.indexing.FileChunk;
import com.contextlayer.backend.indexing.FileChunkRepository;
import com.contextlayer.backend.project.ProjectService;
import com.contextlayer.backend.ranking.RankingResult.RankedFile;
import com.contextlayer.backend.ranking.RankingResult.Ranking;
import com.contextlayer.backend.search.HybridResults.HybridChunk;
import com.contextlayer.backend.summary.FileSummary;
import com.contextlayer.backend.summary.FileSummaryRepository;
import com.contextlayer.backend.symbols.CodeSymbolRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ContextCandidateBuilder {

	private static final int COUNT_EXACTLY_UP_TO_CHARS = 200_000;

	private final ProjectService projectService;
	private final FileSummaryRepository summaryRepository;
	private final CodeSymbolRepository symbolRepository;
	private final FileChunkRepository chunkRepository;
	private final TokenCounter tokens;
	private final List<ContextOptionContributor> contributors; // Spring injects every implementation

	public List<Candidate> build(Long projectId, Ranking ranking, int maxFiles) {
		Path root = Path.of(projectService.getEntity(projectId).getRootPath());
		Map<String, String> summaries = summaryRepository
			.findAllByProjectId(projectId)
			.stream()
			.collect(Collectors.toMap(s -> s.getFile().getRelativePath(), FileSummary::getSummary, (a, b) -> a));

		List<RankedFile> files = ranking.files().stream().limit(maxFiles).toList();
		List<Long> chunkIds = files.stream().flatMap(f -> f.chunks().stream()).map(HybridChunk::chunkId).toList();
		Map<Long, FileChunk> chunks = chunkRepository
			.findAllById(chunkIds)
			.stream()
			.collect(Collectors.toMap(FileChunk::getId, Function.identity()));

		List<Candidate> out = new ArrayList<>();
		for (RankedFile f : files) {
			String content = read(root, f.path());
			List<ChunkText> chunkTexts = f
				.chunks()
				.stream()
				.map(h -> chunks.get(h.chunkId()))
				.filter(Objects::nonNull)
				.map(c -> new ChunkText(c.getStartLine(), c.getEndLine(), c.getContent()))
				.toList();

			OptionContext ctx = new OptionContext(
				f.path(),
				CodeBlocks.languageTag(f.path()),
				content,
				summaries.get(f.path()),
				symbolRepository.findByPath(projectId, f.path()),
				chunkTexts,
				ranking.keywords()
			);

			List<ContextOption> options = new ArrayList<>();
			Set<String> seen = new HashSet<>();
			for (ContextOptionContributor contributor : contributors) {
				for (OptionDraft draft : contributor.contribute(ctx)) {
					if (draft.text() != null && !draft.text().isBlank() && seen.add(draft.text())) {
						options.add(new ContextOption(draft.level(), draft.text(), tokens.count(draft.text())));
					}
				}
			}
			int fullTokens = content == null
				? 0
				: content.length() > COUNT_EXACTLY_UP_TO_CHARS ? content.length() / 4 : tokens.count(content);
			out.add(new Candidate(f.path(), f.score(), f.reasons(), fullTokens, options));
		}
		return out;
	}

	private static String read(Path root, String relative) {
		try {
			return new String(Files.readAllBytes(root.resolve(relative)), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return null;
		}
	}
}
