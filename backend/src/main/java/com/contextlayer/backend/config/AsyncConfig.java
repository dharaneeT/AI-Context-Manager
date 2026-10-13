package com.contextlayer.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {

	/** One worker (a local model can't run jobs in parallel on 8 GB) and a short queue. When full, the API answers 409. */
	@Bean
	public ThreadPoolTaskExecutor taskRunExecutor() {
		ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
		executor.setCorePoolSize(1);
		executor.setMaxPoolSize(1);
		executor.setQueueCapacity(20);
		executor.setThreadNamePrefix("task-run-");
		executor.setWaitForTasksToCompleteOnShutdown(true);
		executor.setAwaitTerminationSeconds(20);
		return executor;
	}
}
