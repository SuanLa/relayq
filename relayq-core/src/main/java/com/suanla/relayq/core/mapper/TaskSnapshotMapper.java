package com.suanla.relayq.core.mapper;

import com.suanla.relayq.core.domain.TaskSnapshot;
import java.util.List;

/** Internal storage contract; no ORM dependency. Single-row lookups return null when absent. */
public interface TaskSnapshotMapper {

    int insert(TaskSnapshot record);

    List<TaskSnapshot> selectByTaskId(long taskId);
}
