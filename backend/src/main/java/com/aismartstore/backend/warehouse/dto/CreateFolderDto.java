package com.aismartstore.backend.warehouse.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * name allows spaces but not accented characters (Story 1 AC-3; spec
 * "Quyet dinh 2 muc 0" - CK_warehouse_folder_name_format mirrors this regex
 * exactly, keep both in sync).
 */
public record CreateFolderDto(
        @NotBlank @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9 _-]+$",
                message = "Folder name must be 1-100 characters: letters, digits, spaces, '_' or '-' only")
        String name,
        UUID parentId) {
}
