package com.suanla.relayq.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.suanla.relayq.core.domain.TaskSnapshot;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TaskSnapshotMapper extends BaseMapper<TaskSnapshot> {

    List<TaskSnapshot> selectByTaskId(@Param("taskId") long taskId);
}
