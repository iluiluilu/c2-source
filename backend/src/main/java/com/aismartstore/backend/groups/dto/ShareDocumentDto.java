package com.aismartstore.backend.groups.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ShareDocumentDto(@NotNull UUID groupId) {
}
