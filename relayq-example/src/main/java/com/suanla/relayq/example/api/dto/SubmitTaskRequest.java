package com.suanla.relayq.example.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;

// Web 层必须使用 Boot 4 MVC 采用的 tools.jackson，core 内部仍使用 com.fasterxml.jackson 处理字符串参数。
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
