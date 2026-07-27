package com.suanla.relayq.core.config;

import lombok.Data;

@Data
public class RelayqProperties {

    /**
     * 实例标识默认留空；运行时按 hostname + pid + 随机后缀生成，作为 §5.4 fencing 的 owner。
     */
    private String instanceId = "";

    private Pull pull = new Pull();
    private Worker worker = new Worker();
    private Lease lease = new Lease();
    private Retry retry = new Retry();
    private Handler handler = new Handler();
    private Snapshot snapshot = new Snapshot();
    private Metrics metrics = new Metrics();

    @Data
    public static class Pull {

        /** §5.2 基础轮询间隔，默认 1000ms。 */
        private long intervalMs = 1_000L;

        /** §5.2 多实例轮询抖动比例，默认 20%。 */
        private double jitterRatio = 0.2D;

        /** §5.2 单次最大抢占量，默认 100。 */
        private int batchSize = 100;

        /** §5.2 连续空拉的退避上限，默认 30s。 */
        private long emptyBackoffMaxMs = 30_000L;

        /** §5.2 空拉指数退避倍率，默认 2。 */
        private double emptyBackoffMultiplier = 2.0D;
    }

    @Data
    public static class Worker {

        /** §4 决策 2 的常驻工作线程数，默认 8。 */
        private int coreSize = 8;

        /** §4 决策 2 的最大工作线程数，默认 32。 */
        private int maxSize = 32;

        /** §4 决策 2 的有界任务队列容量，默认 1000。 */
        private int queueCapacity = 1_000;

        /** §4 决策 2 的非核心线程保活时间，默认 60s。 */
        private long keepAliveSeconds = 60L;

        /** §10 优雅停机等待时间，默认 30s。 */
        private long shutdownGraceSeconds = 30L;
    }

    @Data
    public static class Lease {

        /** §5.4 租约有效期，默认 30s。 */
        private long ttlSeconds = 30L;

        /** §5.4 续租间隔除数，默认 3，即每 ttl/3 续租。 */
        private int renewIntervalDivisor = 3;

        /** §5.6 僵尸任务扫描间隔，默认 5s。 */
        private long reaperIntervalMs = 5_000L;

        /** §5.6 单次回收上限，默认 100。 */
        private int reaperBatchSize = 100;
    }

    @Data
    public static class Retry {

        /** §5.5 新任务默认最大重试次数，默认 3。 */
        private int defaultMaxRetry = 3;

        /** §5.5 首次退避基数，默认 1000ms。 */
        private long baseDelayMs = 1_000L;

        /** §5.5 指数退避倍率，默认 2。 */
        private double multiplier = 2.0D;

        /** §5.5 退避上限，默认 60s。 */
        private long maxDelayMs = 60_000L;

        /** §5.5 重试时间抖动比例，默认 20%。 */
        private double jitterRatio = 0.2D;
    }

    @Data
    public static class Handler {

        /** §5.4 用于缩小租约误判窗口的 handler 超时，默认 30s。 */
        private long timeoutMs = 30_000L;
    }

    @Data
    public static class Snapshot {

        /** §7 快照总开关，默认开启。 */
        private boolean enabled = true;

        /** §7.1 专用单线程池的有界队列容量，默认 4。 */
        private int queueCapacity = 4;

        /** §7.1 单任务快照冷却时间，默认 300s。 */
        private long cooldownSeconds = 300L;

        /** §7.1 进程内令牌桶速率，默认每分钟 5 次。 */
        private int ratePerMinute = 5;

        /** §7.3 线程 dump 按 UTF-8 字节截断上限，默认 1MiB。 */
        private int threadDumpMaxBytes = 1_048_576;

        /** §7.3 超过该线程数时不采集锁信息，默认 1000。 */
        private int lockInfoThreadLimit = 1_000;

        /** §7.3 快照整体采集超时，默认 5s。 */
        private long collectTimeoutMs = 5_000L;

        /** §7 连续失败触发快照的阈值，默认 3。 */
        private int failThreshold = 3;
    }

    @Data
    public static class Metrics {

        /** §9 backlog count(*) 的结果缓存时间，默认 5s。 */
        private long backlogCacheSeconds = 5L;
    }
}
