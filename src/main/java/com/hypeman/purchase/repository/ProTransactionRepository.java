package com.hypeman.purchase.repository;

import com.hypeman.purchase.model.ProTransaction;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProTransactionRepository extends JpaRepository<ProTransaction, UUID> {

    Optional<ProTransaction> findByTransactionId(String transactionId);

    List<ProTransaction> findByUserId(UUID userId);

    Optional<ProTransaction> findFirstByOriginalTransactionId(String originalTransactionId);
}
