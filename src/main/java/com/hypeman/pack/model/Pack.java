package com.hypeman.pack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/** A set of four clips, one per role. A default pack has no creator and is shown to everyone; it's free unless sold. */
@Entity
@Table(name = "packs")
public class Pack {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    private UUID creatorId;

    private String slug;

    private String title;

    private String description;

    private String photoKey;

    private String productId;

    private String priceTier;

    private boolean isFree;

    private boolean isExplicit;

    @Enumerated(EnumType.STRING)
    private PackStatus status = PackStatus.DRAFT;

    private Integer featuredRank;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Pack() {
    }

    /** A free pack with no creator, as the admin makes for everyone. */
    public static Pack defaultPack(String slug, String title, String description, boolean explicit) {
        Pack pack = new Pack();
        pack.slug = slug;
        pack.title = title;
        pack.description = description;
        pack.isFree = true;
        pack.isExplicit = explicit;
        return pack;
    }

    /** Sells the pack as this App Store product, on its own or with Pro. */
    public Pack sellAs(String productId) {
        this.productId = productId;
        this.isFree = false;
        return this;
    }

    public Pack publish(){
        this.status = PackStatus.PUBLISHED;
        return this;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCreatorId() {
        return creatorId;
    }

    public String getSlug() {
        return slug;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public String getPhotoKey() {
        return photoKey;
    }

    /** The App Store product that sells this pack; null for a free pack. */
    public String getProductId() {
        return productId;
    }

    public boolean isFree() {
        return isFree;
    }

    public boolean isExplicit() {
        return isExplicit;
    }

    public PackStatus getStatus() {
        return status;
    }

    public Integer getFeaturedRank() {
        return featuredRank;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
