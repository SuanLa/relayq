package com.suanla.relayq.core.service;

import java.util.List;

public record PageResult<T>(
        List<T> records,
        long total,
        long pageNumber,
        int pageSize) {

    public PageResult {
        records = List.copyOf(records);
    }
}
