package com.aismartstore.backend.personal.dto;

import java.util.UUID;

/** {@code folderId: null} moves the file to the root of the owner's space. */
public record MovePersonalFileDto(UUID folderId) {
}
