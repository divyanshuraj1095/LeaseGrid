package com.leasegrid.leasegrid.allocation;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record AllocationCommand(@NotNull UUID poolId, @NotNull UUID teamId,
        @Positive int quantity, @Min(60) @Max(604800) int durationSeconds,
        boolean queueIfUnavailable) {}
