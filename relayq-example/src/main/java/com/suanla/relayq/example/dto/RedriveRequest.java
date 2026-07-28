package com.suanla.relayq.example.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RedriveRequest {

    @NotBlank(message = "redriveBy must not be blank")
    @Size(max = 64, message = "redriveBy length must not exceed 64")
    private String redriveBy;

    @NotBlank(message = "redriveReason must not be blank")
    @Size(max = 255, message = "redriveReason length must not exceed 255")
    private String redriveReason;
}
