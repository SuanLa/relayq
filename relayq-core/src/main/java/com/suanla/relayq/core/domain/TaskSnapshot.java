package com.suanla.relayq.core.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_snapshot")
public class TaskSnapshot {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("task_id")
    private Long taskId;

    @TableField("execute_log_id")
    private Long executeLogId;

    @TableField("attempt_no")
    private Integer attemptNo;

    @TableField("trigger_type")
    private TriggerType triggerType;

    @TableField("thread_dump")
    private String threadDump;

    @TableField("thread_dump_original_len")
    private Integer threadDumpOriginalLen;

    @TableField("lock_info_included")
    private Boolean lockInfoIncluded;

    @TableField("pool_state")
    private String poolState;

    @TableField("heap_state")
    private String heapState;

    @TableField("backlog_count")
    private Long backlogCount;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
