package com.hypeman.friend.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class FriendshipTests {

    /** A uuid starting with f is negative to Java's signed compare but sorts last in Postgres, whose CHECK must pass. */
    @Test
    void thePairIsOrderedTheWayPostgresComparesUuids() {
        UUID low = UUID.fromString("01900000-0000-7000-8000-000000000000");
        UUID high = UUID.fromString("f1900000-0000-7000-8000-000000000000");

        assertThat(Friendship.Key.of(high, low)).isEqualTo(new Friendship.Key(low, high));
        assertThat(Friendship.Key.of(low, high)).isEqualTo(new Friendship.Key(low, high));
        assertThat(Friendship.between(high, low).other(low)).isEqualTo(high);
    }
}
