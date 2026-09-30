package com.aismartstore.backend.digitization;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * digitization-pipeline-01-source-dispatch.md §4.1 steps 2-3.
 *
 * <p><b>Deviation from the spec's stated mechanism, DEV + user decision
 * (2026-08-30):</b> §0 point 1 / §4.1 step 3 call for
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}. This class uses
 * a plain {@link EventListener} instead. Reason: {@code
 * DocumentsService.ingestBatchFile} runs with no surrounding
 * {@code @Transactional} (each {@code documentRepository.save()} call - see
 * its "commits immediately" comment - opens and commits its own short
 * transaction) and {@code spring.jpa.open-in-view=false}, so by the time
 * {@code ingestBatchFile} publishes {@link DocumentIngestedEvent} right
 * before returning, there is no active Spring transaction synchronization
 * left to attach an {@code AFTER_COMMIT} callback to. Per
 * {@code @TransactionalEventListener}'s documented contract
 * ({@code fallbackExecution=false} by default), that means the event would
 * silently never be delivered - not an edge case, every single ingest call.
 * A plain, synchronous {@code @EventListener} is functionally equivalent to
 * "after commit" here specifically because the event is only published after
 * step 8c's {@code save()} call has already returned, and that save's own
 * transaction has already committed by then. This does not change {@code
 * DocumentsService}'s existing pending -&gt; putObject -&gt; clean write path
 * (still intentionally not wrapped in one transaction, preserving its
 * crash-safety property - see the class Javadoc there).
 */
@Component
public class DigitizationDispatchListener {

    private static final Logger log = LoggerFactory.getLogger(DigitizationDispatchListener.class);

    private final DigitizationIntakeService digitizationIntakeService;

    public DigitizationDispatchListener(DigitizationIntakeService digitizationIntakeService) {
        this.digitizationIntakeService = digitizationIntakeService;
    }

    /**
     * Runs synchronously, in the same thread (and HTTP request) as {@code
     * ingestBatchFile}'s caller - any exception here must not turn an
     * already-successful ingest into a failed HTTP response, so failures are
     * caught and logged rather than propagated. §4.5's reconciliation job is
     * the safety net for anything missed this way.
     */
    @EventListener
    public void onDocumentIngested(DocumentIngestedEvent event) {
        try {
            digitizationIntakeService.handleIngestedDocument(event.documentId(), event.mimeType());
        } catch (RuntimeException e) {
            log.error("Digitization intake failed for document {}", event.documentId(), e);
        }
    }
}
