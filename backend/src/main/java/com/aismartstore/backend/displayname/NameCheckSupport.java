package com.aismartstore.backend.displayname;

import com.aismartstore.backend.common.BadRequestException;
import java.util.ArrayList;
import java.util.List;

/** Shared request handling for the name-check endpoints (limit, NFC, result shaping). */
public final class NameCheckSupport {

    private NameCheckSupport() {
    }

    public static List<NameCheckRepository.Candidate> toCandidates(NameCheckRequest request) {
        if (request.files().size() > NameCheckRequest.MAX_FILES) {
            throw new BadRequestException("Tối đa " + NameCheckRequest.MAX_FILES + " tệp mỗi lần kiểm tra tên");
        }
        List<NameCheckRepository.Candidate> out = new ArrayList<>(request.files().size());
        for (NameCheckRequest.File f : request.files()) {
            String path = f == null || f.relativePath() == null ? "" : DisplayNameValidator.nfc(f.relativePath());
            String name = f == null || f.originalName() == null ? "" : DisplayNameValidator.nfc(f.originalName());
            out.add(new NameCheckRepository.Candidate(path, name));
        }
        return out;
    }

    public static List<NameCheckRequest.Result> toResults(boolean[] taken) {
        List<NameCheckRequest.Result> out = new ArrayList<>(taken.length);
        for (boolean t : taken) {
            out.add(new NameCheckRequest.Result(t));
        }
        return out;
    }

    public static int count(boolean[] taken) {
        int n = 0;
        for (boolean t : taken) {
            if (t) {
                n++;
            }
        }
        return n;
    }
}
