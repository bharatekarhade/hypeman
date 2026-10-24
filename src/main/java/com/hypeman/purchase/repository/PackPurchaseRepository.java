package com.hypeman.purchase.repository;

import com.hypeman.purchase.model.PackPurchase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackPurchaseRepository extends JpaRepository<PackPurchase, UUID> {

    Optional<PackPurchase> findByUserIdAndPackId(UUID userId, UUID packId);

    Optional<PackPurchase> findByOriginalTransactionId(String originalTransactionId);

    List<PackPurchase> findByUserIdAndRevokedAtIsNull(UUID userId);

    boolean existsByUserIdAndPackIdAndRevokedAtIsNull(UUID userId, UUID packId);
}
