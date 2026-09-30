package com.aismartstore.backend.upload;

import com.aismartstore.backend.auth.CurrentUser;
import com.aismartstore.backend.auth.KeycloakUser;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * chunked-file-upload-01-ingest-chunked-upload.md §3: the 3 domain-neutral
 * endpoints of the {@code init -> chunk -> status -> complete} protocol -
 * {@code init} itself is domain-specific and lives elsewhere
 * ({@code IngestController.initUploadSession} for this spec). Every endpoint
 * here only requires a valid JWT (`isAuthenticated()`); the actual access
 * control is "did you create this session" (AC-7), enforced inside
 * {@link UploadSessionService} - see the spec's §3 rationale for why no
 * domain-specific permission is checked at this layer.
 */
@RestController
@RequestMapping("/upload-sessions")
public class UploadSessionController {

    private final UploadSessionService uploadSessionService;

    public UploadSessionController(UploadSessionService uploadSessionService) {
        this.uploadSessionService = uploadSessionService;
    }

    public record ChunkAckView(UUID sessionId, long receivedBytes, long declaredSizeBytes) {
    }

    public record StatusView(
            UUID sessionId,
            String status,
            long receivedBytes,
            long declaredSizeBytes,
            long chunkSizeBytes,
            OffsetDateTime expiresAt) {
        static StatusView of(UploadSession s) {
            return new StatusView(
                    s.getId(), s.getStatus(), s.getReceivedBytes(), s.getDeclaredSizeBytes(),
                    s.getChunkSizeBytes(), s.getExpiresAt());
        }
    }

    @PutMapping(value = "/{sessionId}/chunks", consumes = "application/octet-stream")
    @PreAuthorize("isAuthenticated()")
    public ChunkAckView putChunk(
            @CurrentUser KeycloakUser user,
            @PathVariable UUID sessionId,
            @RequestParam long offset,
            HttpServletRequest request)
            throws IOException {
        UploadSession session = uploadSessionService.appendChunk(user, sessionId, offset, request.getInputStream());
        return new ChunkAckView(session.getId(), session.getReceivedBytes(), session.getDeclaredSizeBytes());
    }

    @GetMapping("/{sessionId}")
    @PreAuthorize("isAuthenticated()")
    public StatusView status(@CurrentUser KeycloakUser user, @PathVariable UUID sessionId) {
        return StatusView.of(uploadSessionService.getStatus(user, sessionId));
    }

    @PostMapping("/{sessionId}/complete")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> complete(@CurrentUser KeycloakUser user, @PathVariable UUID sessionId) {
        UploadSessionService.CompleteResult result = uploadSessionService.complete(user, sessionId);
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }
}
