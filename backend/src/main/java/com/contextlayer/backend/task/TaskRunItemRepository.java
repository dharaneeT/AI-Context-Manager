package com.contextlayer.backend.task;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRunItemRepository extends JpaRepository<TaskRunItem, Long> {
	List<TaskRunItem> findByTaskIdOrderByPosition(Long taskId);
}
