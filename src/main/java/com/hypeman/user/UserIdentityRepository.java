package com.hypeman.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface UserIdentityRepository extends JpaRepository<UserIdentity, UUID> {

    Optional<UserIdentity> findByProviderAndSubject(IdentityProvider provider, String subject);

    List<UserIdentity> findByUserIdOrderByProvider(UUID userId);

    /**
     * 1 if this call created the identity, 0 if it already existed. When another transaction is inserting the same
     * identity, Postgres waits for it to commit before answering, so the existing row is visible right after a 0.
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_identities (id, user_id, provider, subject, email)
            VALUES (:id, :userId, :provider, :subject, :email)
            ON CONFLICT (provider, subject) DO NOTHING""", nativeQuery = true)
    int insertIfAbsent(UUID id, UUID userId, String provider, String subject, String email);

    @Modifying
    @Query("delete from UserIdentity i where i.userId = :userId")
    void deleteByUserId(UUID userId);
}
