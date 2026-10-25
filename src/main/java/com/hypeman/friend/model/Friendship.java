package com.hypeman.friend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Two users who can yell at each other, stored once with the smaller id first. */
@Entity
@Table(name = "friendships")
@IdClass(Friendship.Key.class)
public class Friendship {

    @Id
    @Column(name = "user_a")
    private UUID userA;

    @Id
    @Column(name = "user_b")
    private UUID userB;

    @Column(insertable = false, updatable = false)
    private Instant createdAt;

    @Column(insertable = false, updatable = false)
    private Instant updatedAt;

    protected Friendship() {
    }

    private Friendship(Key key) {
        this.userA = key.userA();
        this.userB = key.userB();
    }

    public static Friendship between(UUID one, UUID other) {
        return new Friendship(Key.of(one, other));
    }

    /** The one of the pair who isn't {@code userId}. */
    public UUID other(UUID userId) {
        return userA.equals(userId) ? userB : userA;
    }

    public UUID getUserA() {
        return userA;
    }

    public UUID getUserB() {
        return userB;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * The pair in the database's order. Postgres compares uuids byte by byte, unsigned, which matches comparing their
     * lowercase text but not {@link UUID#compareTo}, which compares signed longs.
     */
    public record Key(UUID userA, UUID userB) implements Serializable {

        public static Key of(UUID one, UUID other) {
            return one.toString().compareTo(other.toString()) < 0 ? new Key(one, other) : new Key(other, one);
        }
    }
}
