package com.hypeman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** Checks the rules the schema enforces on its own, whatever code writes to it. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("local")
@Transactional
class MigrationTests {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void updatedAtMovesOnUpdate() {
        // Backdated on insert: the trigger fires only on UPDATE, and would overwrite a backdating UPDATE too.
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, updated_at) VALUES (?, now() - interval '1 day')", user);
        OffsetDateTime before = jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", OffsetDateTime.class, user);

        jdbc.update("UPDATE users SET display_name = 'Bharat' WHERE id = ?", user);

        OffsetDateTime after = jdbc.queryForObject("SELECT updated_at FROM users WHERE id = ?", OffsetDateTime.class, user);
        assertThat(after).isAfter(before);
    }

    @Test
    void anAppleAccountBelongsToOneUser() {
        insertIdentity(insertUser(), "APPLE", "001234.abcd");

        assertThatThrownBy(() -> insertIdentity(insertUser(), "APPLE", "001234.abcd"))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void deletingAUserRemovesTheirIdentitiesDevicesAndTokens() {
        UUID user = insertUser();
        insertIdentity(user, "GOOGLE", "1098765");
        UUID device = UUID.randomUUID();
        jdbc.update("INSERT INTO devices (id, user_id) VALUES (?, ?)", device, user);
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, device_id, token_hash, expires_at) VALUES (?, ?, ?, ?, now() + interval '30 days')",
                UUID.randomUUID(), user, device, new byte[32]);

        jdbc.update("DELETE FROM users WHERE id = ?", user);

        for (String table : new String[] {"user_identities", "devices", "refresh_tokens"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE user_id = ?", Integer.class, user))
                    .as(table)
                    .isZero();
        }
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id) VALUES (?)", id);
        return id;
    }

    private void insertIdentity(UUID user, String provider, String subject) {
        jdbc.update("INSERT INTO user_identities (id, user_id, provider, subject) VALUES (?, ?, ?, ?)",
                UUID.randomUUID(), user, provider, subject);
    }
}
