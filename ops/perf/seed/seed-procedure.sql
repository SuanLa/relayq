DROP PROCEDURE IF EXISTS relayq_seed_tasks;

DELIMITER $$

CREATE PROCEDURE relayq_seed_tasks(
    IN p_start_row BIGINT,
    IN p_row_count BIGINT,
    IN p_batch_size INT,
    IN p_pending_percent INT,
    IN p_dead_percent INT
)
BEGIN
    DECLARE v_offset BIGINT;
    DECLARE v_end BIGINT;
    DECLARE v_batch_rows INT;
    DECLARE v_inserted INT;
    DECLARE v_now DATETIME(3) DEFAULT NOW(3);

    IF p_start_row < 0
        OR p_row_count < 1
        OR p_start_row + p_row_count > 100000000 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'requested row range must be within 0 and 100000000';
    END IF;
    IF p_batch_size < 1 OR p_batch_size > 100000 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'batch size must be between 1 and 100000';
    END IF;
    IF p_pending_percent < 0
        OR p_dead_percent < 0
        OR p_pending_percent + p_dead_percent > 100 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'pending/dead percentages are invalid';
    END IF;

    SET v_offset = p_start_row;
    SET v_end = p_start_row + p_row_count;
    SET autocommit = 0;

    WHILE v_offset < v_end DO
        SET v_batch_rows = LEAST(p_batch_size, v_end - v_offset);

        INSERT IGNORE INTO task_info (
            id,
            biz_key,
            handler_name,
            params,
            status,
            scheduled_time,
            retry_count,
            max_retry,
            next_retry_time,
            lease_owner,
            lease_expire_time,
            current_attempt_no,
            trace_id,
            last_failure_kind,
            redrive_by,
            redrive_reason,
            redrive_at,
            error_msg,
            created_at,
            updated_at
        )
        SELECT
            7000000000000000000 + seed_rows.row_no,
            CONCAT('perf-seed-', LPAD(seed_rows.row_no, 12, '0')),
            'load-test-handler',
            JSON_OBJECT('source', 'perf-seed'),
            CASE
                WHEN MOD(seed_rows.row_no, 100) < p_pending_percent THEN 'PENDING'
                WHEN MOD(seed_rows.row_no, 100)
                    < p_pending_percent + p_dead_percent THEN 'DEAD'
                ELSE 'SUCCESS'
            END,
            CASE
                WHEN MOD(seed_rows.row_no, 100) < p_pending_percent
                    THEN DATE_ADD(v_now, INTERVAL 365 DAY)
                ELSE DATE_SUB(
                    v_now,
                    INTERVAL MOD(seed_rows.row_no, 7776000) SECOND)
            END,
            CASE
                WHEN MOD(seed_rows.row_no, 100) >= p_pending_percent
                    AND MOD(seed_rows.row_no, 100)
                        < p_pending_percent + p_dead_percent THEN 3
                ELSE 0
            END,
            3,
            NULL,
            NULL,
            NULL,
            CASE
                WHEN MOD(seed_rows.row_no, 100) < p_pending_percent THEN 0
                ELSE 1
            END,
            LPAD(HEX(seed_rows.row_no), 32, '0'),
            CASE
                WHEN MOD(seed_rows.row_no, 100) >= p_pending_percent
                    AND MOD(seed_rows.row_no, 100)
                        < p_pending_percent + p_dead_percent
                    THEN 'BUSINESS_ERROR'
                ELSE NULL
            END,
            NULL,
            NULL,
            NULL,
            CASE
                WHEN MOD(seed_rows.row_no, 100) >= p_pending_percent
                    AND MOD(seed_rows.row_no, 100)
                        < p_pending_percent + p_dead_percent
                    THEN 'Synthetic dead-letter task generated for performance testing'
                ELSE NULL
            END,
            DATE_SUB(
                v_now,
                INTERVAL MOD(seed_rows.row_no, 7776000) SECOND),
            DATE_SUB(
                v_now,
                INTERVAL MOD(seed_rows.row_no, 7776000) SECOND)
        FROM (
            SELECT
                v_offset
                    + digit0.n
                    + digit1.n * 10
                    + digit2.n * 100
                    + digit3.n * 1000
                    + digit4.n * 10000 AS row_no,
                digit0.n
                    + digit1.n * 10
                    + digit2.n * 100
                    + digit3.n * 1000
                    + digit4.n * 10000 AS batch_no
            FROM
                (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2
                 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
                 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
                 UNION ALL SELECT 9) digit0
            CROSS JOIN
                (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2
                 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
                 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
                 UNION ALL SELECT 9) digit1
            CROSS JOIN
                (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2
                 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
                 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
                 UNION ALL SELECT 9) digit2
            CROSS JOIN
                (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2
                 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
                 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
                 UNION ALL SELECT 9) digit3
            CROSS JOIN
                (SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2
                 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5
                 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8
                 UNION ALL SELECT 9) digit4
        ) seed_rows
        WHERE seed_rows.batch_no < v_batch_rows;

        SET v_inserted = ROW_COUNT();
        COMMIT;
        SET v_offset = v_offset + v_batch_rows;

        SELECT
            v_offset AS processed_rows,
            v_inserted AS inserted_in_batch,
            v_end AS target_rows;
    END WHILE;

    SET autocommit = 1;
END$$

DELIMITER ;
