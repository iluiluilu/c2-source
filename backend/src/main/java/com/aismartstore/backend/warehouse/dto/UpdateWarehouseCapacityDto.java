package com.aismartstore.backend.warehouse.dto;

import jakarta.validation.constraints.NotNull;

/**
 * {@code maxCapacityBytes <= 0} is rejected with the coded
 * {@code 400 INVALID_VALUE} body (spec §3), not a generic Bean Validation
 * message - so the "> 0" rule is checked in
 * {@code WarehouseCapacityService.updateCapacity}, not here.
 */
public record UpdateWarehouseCapacityDto(@NotNull Long maxCapacityBytes) {
}
