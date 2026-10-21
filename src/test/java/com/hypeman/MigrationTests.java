package com.hypeman;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.hypeman.auth.model.Device;
import com.hypeman.auth.model.RefreshToken;
import com.hypeman.auth.repository.DeviceRepository;
import com.hypeman.auth.repository.RefreshTokenRepository;
import com.hypeman.pack.model.Clip;
import com.hypeman.pack.model.ClipRole;
import com.hypeman.pack.model.ClipStatus;
import com.hypeman.pack.model.Pack;
import com.hypeman.pack.model.PackStatus;
import com.hypeman.pack.repository.ClipRepository;
import com.hypeman.pack.repository.PackRepository;
import com.hypeman.user.model.IdentityProvider;
import com.hypeman.user.model.User;
import com.hypeman.user.repository.UserIdentityRepository;
import com.hypeman.user.repository.UserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Checks the rules the schema enforces on its own, through the JPA repositories. Each step runs in its own transaction,
 * so the database's now() moves between them and every read comes from the database, not Hibernate's cache.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("local")
class MigrationTests {

    @Autowired
    UserRepository users;

    @Autowired
    UserIdentityRepository identities;

    @Autowired
    DeviceRepository devices;

    @Autowired
    RefreshTokenRepository refreshTokens;

    @Autowired
    PackRepository packs;

    @Autowired
    ClipRepository clips;

    @Autowired
    TransactionTemplate transaction;

    @Test
    void userUpdatedAtMovesOnUpdate() {
        UUID user = users.save(new User()).getId();
        Instant before = users.findById(user).orElseThrow().getUpdatedAt();

        transaction.executeWithoutResult(status -> users.findById(user).orElseThrow().markDeleted(Instant.now()));

        assertThat(users.findById(user).orElseThrow().getUpdatedAt()).isAfter(before);
    }

    @Test
    void anAppleAccountBelongsToOneUser() {
        UUID first = users.save(new User()).getId();
        UUID second = users.save(new User()).getId();

        assertThat(insertIdentity(first, "001234.abcd")).isEqualTo(1);
        assertThat(insertIdentity(second, "001234.abcd")).as("same Apple account, another user").isZero();
    }

    @Test
    void deletingAUserRemovesTheirIdentitiesDevicesAndTokens() {
        UUID user = users.save(new User()).getId();
        insertIdentity(user, "001234." + user);
        UUID device = devices.save(new Device(UUID.randomUUID(), user, Instant.now())).getId();
        byte[] tokenHash = UUID.randomUUID().toString().getBytes();
        refreshTokens.save(new RefreshToken(user, device, tokenHash, Instant.now().plus(30, ChronoUnit.DAYS)));

        users.deleteById(user);

        assertThat(identities.findByUserIdOrderByProvider(user)).isEmpty();
        assertThat(devices.findById(device)).isEmpty();
        assertThat(refreshTokens.findByTokenHash(tokenHash)).isEmpty();
    }

    @Test
    void everyPackHasItsOwnClipPerRole() {
        UUID first = savePack();
        UUID second = savePack();
        clips.saveAndFlush(new Clip(first, ClipRole.START, "Get up. Do it. Now."));
        clips.saveAndFlush(new Clip(second, ClipRole.START, "Get up. Do it. Now."));

        assertThatThrownBy(() -> clips.saveAndFlush(new Clip(first, ClipRole.START, "Again?")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void newPacksAndClipsStartAsDraftAndUploading() {
        UUID pack = savePack();
        UUID clip = clips.save(new Clip(pack, ClipRole.CONGRATS, "That's what I'm talking about.")).getId();

        assertThat(packs.findById(pack).orElseThrow().getStatus()).isEqualTo(PackStatus.DRAFT);
        assertThat(clips.findById(clip).orElseThrow().getStatus()).isEqualTo(ClipStatus.UPLOADING);
    }

    @Test
    void clipUpdatedAtMovesOnUpdate() {
        UUID clip = clips.save(new Clip(savePack(), ClipRole.NAG, "You snoozed again? Really?")).getId();
        Instant before = clips.findById(clip).orElseThrow().getUpdatedAt();

        transaction.executeWithoutResult(status -> clips.findById(clip).orElseThrow().uploadStarted("packs/a/nag/source.mp4"));

        assertThat(clips.findById(clip).orElseThrow().getUpdatedAt()).isAfter(before);
    }

    @Test
    void deletingAPackRemovesItsClips() {
        UUID pack = savePack();
        clips.save(new Clip(pack, ClipRole.START, "Get up. Do it. Now."));
        clips.save(new Clip(pack, ClipRole.REMINDER, "It's time."));

        packs.deleteById(pack);

        assertThat(clips.findByPackIdOrderByRole(pack)).isEmpty();
    }

    private UUID savePack() {
        return packs.save(Pack.defaultPack("coach-" + UUID.randomUUID(), "Coach", "Yells at you", false)).getId();
    }

    private int insertIdentity(UUID user, String subject) {
        return transaction.execute(status ->
                identities.insertIfAbsent(UUID.randomUUID(), user, IdentityProvider.APPLE.name(), subject, null));
    }
}
