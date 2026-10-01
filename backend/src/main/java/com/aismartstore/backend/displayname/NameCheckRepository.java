package com.aismartstore.backend.displayname;

import java.sql.Array;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * SPEC-folder-upload-display-name R14: name lookups use the SAME expression as the unique indexes
 * {@code UQ_document_display_name_alive} / {@code UQ_personal_file_display_name}
 * ({@code COALESCE(folder, sentinel)} + {@code md5(relative_path || '/' || lower(display_name))}),
 * with {@code lower}/{@code md5} evaluated by Postgres, never in Java.
 */
@Repository
public class NameCheckRepository {

    public static final UUID SENTINEL = new UUID(0L, 0L);

    /** One (relativePath, name) candidate; both already NFC. */
    public record Candidate(String relativePath, String name) {
    }

    private static final String WAREHOUSE_SQL =
            "SELECT t.ord FROM unnest(?::text[], ?::text[]) WITH ORDINALITY AS t(p, n, ord) "
                    + "WHERE EXISTS (SELECT 1 FROM document d "
                    + "WHERE COALESCE(d.folder_id, '00000000-0000-0000-0000-000000000000'::uuid) = ?::uuid "
                    + "AND md5(d.relative_path || '/' || lower(d.display_name)) = md5(t.p || '/' || lower(t.n)) "
                    + "AND d.deleted_at IS NULL AND d.status <> 'rejected')";

    private static final String PERSONAL_SQL =
            "SELECT t.ord FROM unnest(?::text[], ?::text[]) WITH ORDINALITY AS t(p, n, ord) "
                    + "WHERE EXISTS (SELECT 1 FROM personal_file f "
                    + "WHERE f.owner_sub = ? "
                    + "AND COALESCE(f.folder_id, '00000000-0000-0000-0000-000000000000'::uuid) = ?::uuid "
                    + "AND md5(f.relative_path || '/' || lower(f.display_name)) = md5(t.p || '/' || lower(t.n)))";

    private final JdbcTemplate jdbcTemplate;

    public NameCheckRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Returns, in input order, whether each candidate name is already taken in the warehouse folder. */
    public boolean[] warehouseTaken(UUID folderId, List<Candidate> candidates) {
        UUID key = folderId == null ? SENTINEL : folderId;
        return query(WAREHOUSE_SQL, candidates, ps -> ps.setString(3, key.toString()));
    }

    public boolean[] personalTaken(String ownerSub, UUID folderId, List<Candidate> candidates) {
        UUID key = folderId == null ? SENTINEL : folderId;
        return query(PERSONAL_SQL, candidates, ps -> {
            ps.setString(3, ownerSub);
            ps.setString(4, key.toString());
        });
    }

    private interface Extra {
        void set(java.sql.PreparedStatement ps) throws java.sql.SQLException;
    }

    private boolean[] query(String sql, List<Candidate> candidates, Extra extra) {
        boolean[] taken = new boolean[candidates.size()];
        if (candidates.isEmpty()) {
            return taken;
        }
        String[] paths = new String[candidates.size()];
        String[] names = new String[candidates.size()];
        for (int i = 0; i < paths.length; i++) {
            paths[i] = candidates.get(i).relativePath();
            names[i] = candidates.get(i).name();
        }
        Set<Integer> hit = new HashSet<>();
        jdbcTemplate.execute((java.sql.Connection con) -> {
            Array pa = con.createArrayOf("text", paths);
            Array na = con.createArrayOf("text", names);
            try (java.sql.PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setArray(1, pa);
                ps.setArray(2, na);
                extra.set(ps);
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        hit.add((int) rs.getLong(1) - 1);
                    }
                }
            } finally {
                pa.free();
                na.free();
            }
            return null;
        });
        for (int i : hit) {
            taken[i] = true;
        }
        return taken;
    }
}
