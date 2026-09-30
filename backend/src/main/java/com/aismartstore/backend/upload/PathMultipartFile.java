package com.aismartstore.backend.upload;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.springframework.web.multipart.MultipartFile;

/**
 * Adapts an on-disk file (the ráp'd-together chunk temp file) to
 * {@link MultipartFile} so {@code complete()} can hand it to
 * {@code DocumentsService.ingestBatchFile} unchanged
 * (chunked-file-upload-01-ingest-chunked-upload.md §4.4 step 5) - reused
 * as-is by chunked-file-upload-02-personal-chunked-upload.md.
 */
public class PathMultipartFile implements MultipartFile {

    private final Path path;
    private final String originalName;
    private final String contentType;

    public PathMultipartFile(Path path, String originalName, String contentType) {
        this.path = path;
        this.originalName = originalName;
        this.contentType = contentType;
    }

    @Override
    public String getName() {
        return "file";
    }

    @Override
    public String getOriginalFilename() {
        return originalName;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public boolean isEmpty() {
        return getSize() == 0;
    }

    @Override
    public long getSize() {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    @Override
    public byte[] getBytes() throws IOException {
        return Files.readAllBytes(path);
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return Files.newInputStream(path);
    }

    @Override
    public void transferTo(File dest) throws IOException, IllegalStateException {
        Files.copy(path, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
