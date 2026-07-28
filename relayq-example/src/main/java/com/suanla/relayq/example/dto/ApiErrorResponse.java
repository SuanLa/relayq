package com.suanla.relayq.example.dto;

import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
public class ApiErrorResponse {

    private Instant timestamp;
    private int status;
    private String error;
    private String message;
    private String path;
    private String traceId;
    private List<String> validationErrors;
}
