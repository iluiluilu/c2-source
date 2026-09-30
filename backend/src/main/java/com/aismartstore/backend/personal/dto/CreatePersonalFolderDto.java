package com.aismartstore.backend.personal.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record CreatePersonalFolderDto(@NotBlank @Size(max = 255) String name, UUID parentId) {
}
