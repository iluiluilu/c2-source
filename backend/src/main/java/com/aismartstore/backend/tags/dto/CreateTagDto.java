package com.aismartstore.backend.tags.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * classification-notes-01-tags.md §3: {@code POST /tags} chỉ còn dùng để tạo
 * {@code topic} - {@code kind} chấp cả 2 giá trị hợp lệ ở tầng validation
 * (khớp {@link com.aismartstore.backend.tags.TagKinds#ALL}), nhưng
 * {@code TagsService.create} từ chối {@code doc_type} với 400 (US3 AC-6).
 */
public record CreateTagDto(
        @NotBlank @Pattern(regexp = "topic|doc_type") String kind,
        @NotBlank @Size(max = 128) String label) {
}
