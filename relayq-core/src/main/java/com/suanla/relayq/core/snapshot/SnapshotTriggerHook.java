package com.suanla.relayq.core.snapshot;

@FunctionalInterface
public interface SnapshotTriggerHook {

    SnapshotTriggerHook NOOP = trigger -> {
    };

    void trigger(SnapshotTrigger trigger);
}
