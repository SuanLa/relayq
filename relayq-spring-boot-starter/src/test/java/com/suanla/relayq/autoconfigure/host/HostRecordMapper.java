package com.suanla.relayq.autoconfigure.host;

/** Host-only mapper loaded from XML; deliberately has no compile-time ORM dependency. */
public interface HostRecordMapper {
    int insert(String name);
    String selectName();
}
