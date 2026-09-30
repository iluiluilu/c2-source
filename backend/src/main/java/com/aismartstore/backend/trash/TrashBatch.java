package com.aismartstore.backend.trash;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * One "lượt xóa" (delete-restore-trash-01-xoa-mem-thung-rac.md §2.1,
 * Quyết định 1 SA): groups the documents a single {@code POST
 * /documents/trash} call moved to trash together, so they can later be
 * identified as having come from the same batch ({@code
 * Document#trashBatchId}). Deliberately holds no per-document rows of its
 * own - {@code item_count} is a denormalized snapshot, not a live count.
 */
@Entity
@Table(name = "trash_batch")
public class TrashBatch {

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;

    @Column(name = "actor_sub", nullable = false)
    private String actorSub;

    @Column(name = "actor_username", nullable = false)
    private String actorUsername;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    public UUID getId() {
        return id;
    }

    public String getActorSub() {
        return actorSub;
    }

    public void setActorSub(String actorSub) {
        this.actorSub = actorSub;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }
}
