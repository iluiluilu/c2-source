package com.aismartstore.backend.accounts.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record CreateAccountDto(
        @NotBlank @Size(min = 3, max = 64) @Pattern(regexp = "^[a-z0-9._-]+$") String username,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @Email String email,
        @NotEmpty List<String> roles,
        @NotBlank @Size(min = 8, max = 128) String password,
        Boolean temporary,
        Boolean enabled) {
}
