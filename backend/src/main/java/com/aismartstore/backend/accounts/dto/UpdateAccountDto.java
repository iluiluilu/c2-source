package com.aismartstore.backend.accounts.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * All fields optional. {@code roles == null} means "leave roles unchanged";
 * {@code roles} present (even empty) replaces the assignable-role set.
 */
public record UpdateAccountDto(
        @Size(max = 100) String firstName, @Size(max = 100) String lastName, @Email String email, List<String> roles) {
}
