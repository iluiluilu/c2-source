package com.aismartstore.backend.scanning;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AllowedFileFormatRepository extends JpaRepository<AllowedFileFormat, String> {
}
