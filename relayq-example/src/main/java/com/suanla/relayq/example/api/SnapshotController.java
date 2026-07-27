package com.suanla.relayq.example.api;

import com.suanla.relayq.core.snapshot.SnapshotAdmission;
import com.suanla.relayq.example.api.dto.ManualSnapshotRequest;
import com.suanla.relayq.example.api.dto.SnapshotAcceptedResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/snapshots")
public class SnapshotController {

    private final SnapshotAdmission snapshotAdmission;

    public SnapshotController(SnapshotAdmission snapshotAdmission) {
        this.snapshotAdmission = snapshotAdmission;
    }

    @PostMapping("/manual")
    public ResponseEntity<SnapshotAcceptedResponse> triggerManual(
            @Valid @RequestBody(required = false) ManualSnapshotRequest request) {
        Long taskId = request == null ? null : request.getTaskId();
        Integer attemptNo = request == null ? null : request.getAttemptNo();
        // 手动入口也必须经过 admission 的去重、冷却、令牌桶和有界队列。
        snapshotAdmission.triggerManual(taskId, attemptNo);

        SnapshotAcceptedResponse response = new SnapshotAcceptedResponse();
        response.setAccepted(snapshotAdmission.isEnabled());
        response.setTaskId(taskId);
        response.setAttemptNo(attemptNo);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }
}
