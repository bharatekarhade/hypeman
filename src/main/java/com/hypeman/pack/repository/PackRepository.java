package com.hypeman.pack.repository;

import com.hypeman.pack.model.Pack;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackRepository extends JpaRepository<Pack, UUID> {

    Optional<Pack> findBySlug(String slug);
}
