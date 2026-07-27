package com.suanla.relayq.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.suanla.relayq.core.domain.TaskExecuteLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TaskExecuteLogMapper extends BaseMapper<TaskExecuteLog> {

    /*
     * insert 直接使用 BaseMapper：uk_task_attempt 冲突会原样进入 Spring 数据访问异常翻译，
     * 调用方可据 DuplicateKeyException 识别旧 worker 的重复审计写入。
     */
    TaskExecuteLog selectByTaskAndAttempt(
            @Param("taskId") long taskId,
            @Param("attemptNo") int attemptNo);

    List<TaskExecuteLog> selectByTaskId(@Param("taskId") long taskId);
}
