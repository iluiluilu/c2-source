package com.aismartstore.backend.accounts.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResetPasswordDto(@NotBlank @Size(min = 8, max = 128) String password, Boolean temporary) {
}
