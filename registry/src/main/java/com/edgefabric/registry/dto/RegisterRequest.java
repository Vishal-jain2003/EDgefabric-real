package com.edgefabric.registry.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Cache Node ID is required and cannot be empty")
    private String cacheNodeId;

    @NotBlank(message = "host cannot be blank or empty")
    @Pattern(
            regexp = "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$",
            message = "Invalid IPv4 address"
    )
    private String host;


    @Min(value = 1, message = "port value can't be less than 1")
    @Max(value = 65535, message = "port can't have value greater than 65535")
    private int port;
}
