package com.contextlayer.backend.pipeline;

import com.contextlayer.backend.git.GitResponses.SyncResult;
import com.contextlayer.backend.graph.GraphBuildResult;
import com.contextlayer.backend.indexing.IndexResult;
import com.contextlayer.backend.summary.SummaryRefreshResult;

public record RefreshReport(
	IndexResult index,
	SyncResult git,
	String gitNote,
	GraphBuildResult graph,
	SummaryRefreshResult summaries
) {}
