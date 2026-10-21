package com.hypeman.pack.repository;

import com.hypeman.pack.model.Clip;
import com.hypeman.pack.model.ClipRole;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClipRepository extends JpaRepository<Clip, UUID> {

    List<Clip> findByPackIdOrderByRole(UUID packId);

    Optional<Clip> findByPackIdAndRole(UUID packId, ClipRole role);
}
