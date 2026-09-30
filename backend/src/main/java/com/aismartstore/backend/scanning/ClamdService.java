package com.aismartstore.backend.scanning;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Minimal clamd TCP client speaking the z-terminated PING/INSTREAM commands
 * (https://linux.die.net/man/8/clamd). Hand-rolled deliberately, matching the
 * NestJS backend's scanning/clamd.service.ts: the protocol is small, and
 * every avoided dependency matters for the air-gapped deployment (NFR-11).
 *
 * INSTREAM framing: {@code zINSTREAM<NUL>} then chunks each prefixed with a
 * 4-byte big-endian length, terminated by a zero-length chunk. clamd replies
 * {@code stream: OK} or {@code stream: <signature> FOUND}.
 */
@Service
public class ClamdService {

    private static final Logger log = LoggerFactory.getLogger(ClamdService.class);

    private static final char NUL = (char) 0;

    private static final Pattern FOUND_PATTERN = Pattern.compile("^stream: (.+) FOUND$");

    private final ScanningProperties properties;

    public ClamdService(ScanningProperties properties) {
        this.properties = properties;
    }

    public record ScanVerdict(boolean clean, String signature) {
        public static ScanVerdict ofClean() {
            return new ScanVerdict(true, null);
        }

        public static ScanVerdict infected(String signature) {
            return new ScanVerdict(false, signature);
        }
    }

    public boolean ping() {
        try {
            return "PONG".equals(command("PING", null));
        } catch (Exception e) {
            return false;
        }
    }

    public ScanVerdict scanStream(InputStream in) {
        String reply = command("INSTREAM", out -> {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(new byte[] {
                        (byte) (read >>> 24), (byte) (read >>> 16), (byte) (read >>> 8), (byte) read,
                });
                out.write(buffer, 0, read);
            }
            out.write(new byte[] {0, 0, 0, 0});
            out.flush();
        });

        Matcher found = FOUND_PATTERN.matcher(reply);
        if (found.matches()) {
            return ScanVerdict.infected(found.group(1));
        }
        if ("stream: OK".equals(reply)) {
            return ScanVerdict.ofClean();
        }
        // e.g. "INSTREAM size limit exceeded" or an ERROR reply - fail closed.
        log.error("Unexpected clamd reply: {}", reply);
        throw new ScannerUnavailableException("unexpected clamd reply: " + reply);
    }

    @FunctionalInterface
    private interface BodyWriter {
        void write(OutputStream out) throws IOException;
    }

    /** Runs a command and returns clamd's full (NUL-terminated) reply. */
    private String command(String cmd, BodyWriter writeBody) {
        try (Socket socket = new Socket()) {
            socket.connect(
                    new InetSocketAddress(properties.clamdHost(), properties.clamdPort()),
                    properties.socketTimeoutMs());
            socket.setSoTimeout(properties.socketTimeoutMs());

            OutputStream out = socket.getOutputStream();
            out.write(("z" + cmd + NUL).getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (writeBody != null) {
                writeBody.write(out);
            }
            socket.shutdownOutput();

            ByteArrayOutputStream received = new ByteArrayOutputStream();
            InputStream in = socket.getInputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = in.read(chunk)) != -1) {
                received.write(chunk, 0, read);
            }
            String raw = received.toString(StandardCharsets.UTF_8);
            int nulIndex = raw.indexOf(NUL);
            return (nulIndex >= 0 ? raw.substring(0, nulIndex) : raw).trim();
        } catch (IOException e) {
            throw new ScannerUnavailableException("clamd connection failed: " + e.getMessage());
        }
    }
}
