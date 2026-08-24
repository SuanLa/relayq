package com.suanla.relayq.core.scheduler;

import com.suanla.relayq.core.config.RelayqProperties;
import com.suanla.relayq.core.mapper.TaskInfoMapper;
import com.suanla.relayq.core.metrics.RelayqMetrics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LeaseReaperTest {

    @Test
    void doesNotIssueUpdateWhenNoExpiredLeaseWasSelected() {
        TaskInfoMapper mapper = mock(TaskInfoMapper.class);
        when(mapper.selectExpiredLeaseIds(10)).thenReturn(List.of());

        try (LeaseReaper reaper = new LeaseReaper(mapper, leaseProperties(10))) {
            assertEquals(0, reaper.reapOnce());
        }

        verify(mapper).selectExpiredLeaseIds(10);
        verify(mapper, never()).reclaimExpiredLeasesByIds(anyCollection());
    }

    @Test
    void sortsCandidateIdsBeforeReclaimingThem() {
        TaskInfoMapper mapper = mock(TaskInfoMapper.class);
        RelayqMetrics metrics = mock(RelayqMetrics.class);
        List<Long> candidates = new ArrayList<>(List.of(30L, 10L, 20L));
        when(mapper.selectExpiredLeaseIds(3)).thenReturn(candidates);
        when(mapper.reclaimExpiredLeasesByIds(candidates)).thenReturn(2);

        int affected;
        try (LeaseReaper reaper = new LeaseReaper(mapper, leaseProperties(3), metrics)) {
            affected = reaper.reapOnce();
        }

        assertEquals(2, affected);
        verify(mapper).reclaimExpiredLeasesByIds(List.of(10L, 20L, 30L));
        verify(metrics).recordLeaseReclaimed(2);
    }

    private static RelayqProperties.Lease leaseProperties(int batchSize) {
        RelayqProperties.Lease properties = new RelayqProperties.Lease();
        properties.setReaperBatchSize(batchSize);
        properties.setReaperIntervalMs(1_000L);
        return properties;
    }
}
