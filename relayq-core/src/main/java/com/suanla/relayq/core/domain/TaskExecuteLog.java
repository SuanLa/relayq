package com.suanla.relayq.core.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_execute_log")
public class TaskExecuteLog {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("task_id")
    private Long taskId;

    @TableField("attempt_no")
    private Integer attemptNo;

    @TableField("start_time")
    private LocalDateTime startTime;

    @TableField("end_time")
    private LocalDateTime endTime;

    @TableField("outcome")
    private ExecuteOutcome outcome;

    @TableField("failure_kind")
    private FailureKind failureKind;

    @TableField("error_stack")
    private String errorStack;

    @TableField("error_stack_original_len")
    private Integer errorStackOriginalLen;

    @TableField("worker_instance")
    private String workerInstance;

    @TableField("trace_id")
    private String traceId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
