package com.aismartstore.backend.warehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Same name rule as {@link CreateFolderDto} (spec "Quyet dinh 2 muc 0"). */
public record RenameFolderDto(
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9 _-]+$",
                message = "Folder name must be 1-100 characters: letters, digits, spaces, '_' or '-' only")
        String name) {
}
