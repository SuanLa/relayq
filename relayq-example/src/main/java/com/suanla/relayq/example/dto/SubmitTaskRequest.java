package com.suanla.relayq.example.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SubmitTaskRequest {

    @NotBlank(message = "bizKey must not be blank")
    @Size(max = 128, message = "bizKey length must not exceed 128")
    private String bizKey;

    @NotBlank(message = "handlerName must not be blank")
    @Size(max = 128, message = "handlerName length must not exceed 128")
    private String handlerName;

    private JsonNode params;

    private LocalDateTime scheduledTime;

    @PositiveOrZero(message = "delaySeconds must not be negative")
    private Long delaySeconds;

    @PositiveOrZero(message = "maxRetry must not be negative")
    private Integer maxRetry;

    @JsonIgnore
    @AssertTrue(message = "scheduledTime and delaySeconds must not both be set")
    public boolean isScheduleSelectionValid() {
        return scheduledTime == null || delaySeconds == null;
    }
}
