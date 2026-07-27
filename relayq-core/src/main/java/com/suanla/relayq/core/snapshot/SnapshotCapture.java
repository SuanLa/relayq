package com.suanla.relayq.core.snapshot;

import java.util.Map;
import java.util.Objects;

public record SnapshotCapture(
        SnapshotTrigger trigger,
        String threadDump,
        boolean lockInfoIncluded,
        Map<String, Object> poolState,
        Map<String, Long> heapState,
        long backlogCount) {

    public SnapshotCapture {
        Objects.requireNonNull(trigger, "trigger must not be null");
        Objects.requireNonNull(poolState, "poolState must not be null");
        Objects.requireNonNull(heapState, "heapState must not be null");
    }
}
