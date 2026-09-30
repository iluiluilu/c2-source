package com.aismartstore.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code app.queue.*} (digitization-pipeline-01-source-dispatch.md
 * §4.6) - queue names are read from env ({@code QUEUE_OCR_NAME}/
 * {@code QUEUE_STT_NAME}/{@code QUEUE_VIDEO_NAME}) rather than hard-coded,
 * per digitization-pipeline.md Quyet dinh 2.
 */
@ConfigurationProperties(prefix = "app.queue")
public record QueueProperties(String ocrName, String sttName, String videoName) {

    /**
     * digitization-pipeline-02-ocr-stt-processing.md §4.4/§4.5, extended by
     * digitization-pipeline-03-video-queue.md §4.2 - resolves the main queue
     * name for a {@code digitization_run.task} value ({@code "ocr"}/
     * {@code "stt"}/{@code "video"}) without this config class depending on
     * the {@code digitization.results.DigitizationRun} domain constants. Any
     * value other than {@code "stt"}/{@code "video"} defaults to the OCR
     * queue, matching {@code DigitizationDispatchService}'s pre-existing
     * mime-type routing (only "ocr"/"stt"/"video" are ever produced there).
     */
    public String forTask(String task) {
        if ("stt".equals(task)) {
            return sttName;
        }
        if ("video".equals(task)) {
            return videoName;
        }
        return ocrName;
    }
}
