package com.contextlayer.backend.task;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** A task that was RUNNING when the app stopped will never finish. Say so instead of leaving it spinning. */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskRecovery implements ApplicationRunner {

	private final TaskRunRepository repository;

	@Override
	public void run(ApplicationArguments args) {
		int fixed = repository.markInterrupted(Instant.now());
		if (fixed > 0) {
			log.warn("{} task(s) were interrupted by the last shutdown and were marked FAILED", fixed);
		}
	}
}
