package com.hypeman.user.repository;

import com.hypeman.user.model.User;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

public interface UserRepository extends JpaRepository<User, UUID> {

    /** Holds the row until the transaction ends, so changes worked out from the user's other rows don't race. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<User> findLockedById(UUID id);
}
