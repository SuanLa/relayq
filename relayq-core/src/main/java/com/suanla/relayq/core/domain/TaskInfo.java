package com.suanla.relayq.core.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("task_info")
public class TaskInfo {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("biz_key")
    private String bizKey;

    @TableField("handler_name")
    private String handlerName;

    @TableField("params")
    private String params;

    @TableField("status")
    private TaskStatus status;

    @TableField("scheduled_time")
    private LocalDateTime scheduledTime;

    @TableField("retry_count")
    private Integer retryCount;

    @TableField("max_retry")
    private Integer maxRetry;

    @TableField("next_retry_time")
    private LocalDateTime nextRetryTime;

    @TableField("lease_owner")
    private String leaseOwner;

    @TableField("lease_expire_time")
    private LocalDateTime leaseExpireTime;

    @TableField("current_attempt_no")
    private Integer currentAttemptNo;

    @TableField("trace_id")
    private String traceId;

    @TableField("last_failure_kind")
    private FailureKind lastFailureKind;

    @TableField("redrive_by")
    private String redriveBy;

    @TableField("redrive_reason")
    private String redriveReason;

    @TableField("redrive_at")
    private LocalDateTime redriveAt;

    @TableField("error_msg")
    private String errorMsg;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
