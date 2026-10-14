package com.aismartstore.backend.documents;

import com.aismartstore.backend.common.NotFoundException;
import com.aismartstore.backend.storage.ObjectStorageService;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/**
 * search-fulltext-05-frontend.md §1/§3.1 (Task 1): the PDFBox page-render
 * logic that used to live private inside {@code ReviewController} (digitization-
 * review-05-frontend.md §3.1), pulled out into a shared bean so both {@code
 * ReviewController} (review-only, {@code runId}-scoped) and {@code
 * DocumentsController} (general read scope, §3.1 of this spec) can render the
 * same page image without copy-pasting the PDFBox/ImageIO code. Behavior is
 * unchanged from the original - pure refactor.
 */
@Component
public class PageImageRenderer {

    private final ObjectStorageService objectStorageService;

    public PageImageRenderer(ObjectStorageService objectStorageService) {
        this.objectStorageService = objectStorageService;
    }

    /** One rendered/pass-through page image plus the content type to serve it as. */
    public record PageImage(byte[] bytes, MediaType contentType) {
    }

    /**
     * §3.1: PDF -> PDFBox render at 150 DPI, encoded to PNG (no server-side
     * cache - "render lại mỗi request" for the first cut); a single-image
     * source (mime {@code image/*}) only ever has 1 "page", passed through
     * unchanged; anything else is not a page-renderable mime type.
     *
     * @param pageNo 1-indexed on the wire (matches {@code
     *               content_segment.page_no}) - PDFBox's own paging is
     *               0-indexed internally.
     */
    public PageImage render(Document document, int pageNo) {
        if (document.getStorageKey() == null) {
            throw new NotFoundException("Tài liệu không có nội dung gốc được lưu trữ: " + document.getId());
        }

        String mimeType = document.getMimeType();
        if (MediaType.APPLICATION_PDF_VALUE.equals(mimeType)) {
            return renderPdfPage(document, pageNo);
        } else if (mimeType != null && mimeType.startsWith("image/")) {
            if (pageNo != 1) {
                throw new NotFoundException("Trang " + pageNo + " không tồn tại (tệp ảnh chỉ có 1 trang)");
            }
            return new PageImage(readObject(document), MediaType.parseMediaType(mimeType));
        } else {
            throw new NotFoundException("Định dạng không hiển thị được theo trang: " + mimeType);
        }
    }

    private PageImage renderPdfPage(Document document, int pageNo) {
        if (pageNo < 1) {
            throw new NotFoundException("Số trang phải lớn hơn hoặc bằng 1");
        }
        try (PDDocument pdf = Loader.loadPDF(readObject(document))) {
            if (pageNo > pdf.getNumberOfPages()) {
                throw new NotFoundException("Trang " + pageNo + " không tồn tại trong tệp PDF này");
            }
            PDFRenderer renderer = new PDFRenderer(pdf);
            BufferedImage rendered = renderer.renderImageWithDPI(pageNo - 1, 150, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rendered, "png", out);
            return new PageImage(out.toByteArray(), MediaType.IMAGE_PNG);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render PDF page " + pageNo, e);
        }
    }

    private byte[] readObject(Document document) {
        try (var stream = objectStorageService.getObject(document.getStorageKey())) {
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read stored object for document " + document.getId(), e);
        }
    }
}
