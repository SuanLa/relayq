-- RelayQ 表结构（唯一权威定义）
-- docker-compose 会把本文件挂到 MySQL 的 /docker-entrypoint-initdb.d/ 下自动执行，
-- 因此不要在别处复制一份 DDL。设计理由见 docs/architecture.md §8。

CREATE TABLE IF NOT EXISTS task_info
(
    id                 BIGINT       NOT NULL COMMENT '雪花 ID',
    biz_key            VARCHAR(128) NOT NULL COMMENT '业务幂等键，提交去重靠它',
    handler_name       VARCHAR(128) NOT NULL COMMENT '执行器名，提交时即校验已注册',
    params             JSON         NULL COMMENT '任务参数',

    status             VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/DEAD/CANCELLED',
    scheduled_time     DATETIME(3)  NOT NULL COMMENT '到期时间，延迟任务的核心字段',

    retry_count        INT          NOT NULL DEFAULT 0 COMMENT '已失败次数，驱动退避与是否进死信',
    max_retry          INT          NOT NULL DEFAULT 3,
    next_retry_time    DATETIME(3)  NULL,

    -- 租约与 fencing：lease_owner 是终态回写的前置断言，缺了它旧 worker 复活会脏写
    lease_owner        VARCHAR(128) NULL COMMENT '当前租约持有实例',
    lease_expire_time  DATETIME(3)  NULL COMMENT '租约到期时间，reaper 据此回收',

    -- 执行审计身份：抢占时自增，与 retry_count 是两回事。
    -- retry_count 只在「业务失败」时 +1；current_attempt_no 在「每次被抢占」时 +1。
    -- 租约丢失后重新抢占不算业务失败，retry_count 不变，但必须拿到新的 attempt_no，
    -- 否则 task_execute_log.uk_task_attempt 会撞唯一键。
    current_attempt_no INT          NOT NULL DEFAULT 0 COMMENT '执行尝试序号，抢占时自增',

    trace_id           VARCHAR(64)  NULL COMMENT '提交时生成，贯穿执行日志',
    last_failure_kind  VARCHAR(32)  NULL COMMENT '最近一次失败分类，区分可重试与毒任务',

    -- 死信重投审计
    redrive_by         VARCHAR(64)  NULL,
    redrive_reason     VARCHAR(255) NULL,
    redrive_at         DATETIME(3)  NULL,

    error_msg          VARCHAR(1024) NULL,
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_biz_key (biz_key),

    -- 拉取 SQL 的核心索引：等值列 status 在前，范围列 scheduled_time 在后。
    -- 列序反过来会让 status 无法参与索引定位，只能靠 scheduled_time 范围扫后回表过滤。
    -- D17 压测要拿这个索引做前后对比。
    KEY idx_status_scheduled (status, scheduled_time),

    KEY idx_status_lease (status, lease_expire_time) COMMENT 'reaper 扫描僵尸任务',
    KEY idx_status_created (status, created_at) COMMENT '死信列表分页'
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='任务主表';


CREATE TABLE IF NOT EXISTS task_execute_log
(
    id                       BIGINT       NOT NULL,
    task_id                  BIGINT       NOT NULL,
    attempt_no               INT          NOT NULL COMMENT '取自抢占时固定的 task_info.current_attempt_no',

    start_time               DATETIME(3)  NOT NULL,
    end_time                 DATETIME(3)  NULL,

    outcome                  VARCHAR(16)  NULL COMMENT 'SUCCESS / FAILED / LEASE_LOST',
    failure_kind             VARCHAR(32)  NULL,

    -- 按字节截断（不是按字符：按字符截会把 UTF-8 多字节序列截断成非法字节）
    error_stack              MEDIUMTEXT   NULL,
    error_stack_original_len INT          NULL COMMENT '截断前原长，为 NULL 表示未截断',

    worker_instance          VARCHAR(128) NOT NULL,
    trace_id                 VARCHAR(64)  NULL,
    created_at               DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    -- 一次抢占只允许一条执行记录。旧 worker 丢租约后复活若想补写，会被这里挡下。
    UNIQUE KEY uk_task_attempt (task_id, attempt_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='执行审计，每次尝试一条';


CREATE TABLE IF NOT EXISTS task_snapshot
(
    id                       BIGINT      NOT NULL,
    task_id                  BIGINT      NULL COMMENT '手动全局触发时可为空',
    execute_log_id           BIGINT      NULL,
    attempt_no               INT         NULL COMMENT '与 task_id 合成去重键，防同一现场抓两份',

    trigger_type             VARCHAR(16) NOT NULL COMMENT 'DEAD / FAIL_THRESHOLD / MANUAL',

    thread_dump              MEDIUMTEXT  NULL,
    thread_dump_original_len INT         NULL,
    lock_info_included       TINYINT(1)  NOT NULL DEFAULT 1 COMMENT '线程数超限时降级为 0，放弃锁信息保延迟',

    pool_state               JSON        NULL COMMENT 'worker/snapshot/requeue 三池水位',
    heap_state               JSON        NULL,
    backlog_count            BIGINT      NULL,

    created_at               DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    KEY idx_task_attempt (task_id, attempt_no),
    KEY idx_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='事故快照';
