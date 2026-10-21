package com.hypeman.pack.repository;

import com.hypeman.pack.model.Clip;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClipRepository extends JpaRepository<Clip, UUID> {

    List<Clip> findByPackIdOrderByRole(UUID packId);
}
