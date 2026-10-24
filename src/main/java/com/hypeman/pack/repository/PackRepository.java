package com.hypeman.pack.repository;

import com.hypeman.pack.model.Pack;
import com.hypeman.pack.model.PackStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PackRepository extends JpaRepository<Pack, UUID> {

    Optional<Pack> findBySlug(String slug);

    /** The pack sold as this App Store product. */
    Optional<Pack> findByProductId(String productId);

    /** The slug itself and any numbered ones (base-2, base-3, …). Slugs are only a-z, 0-9 and -, so no like wildcards. */
    @Query("select p.slug from Pack p where p.slug = :base or p.slug like concat(:base, '-%')")
    Set<String> findSlugsLike(String base);

    List<Pack> findByStatusOrderByFeaturedRankAsc(PackStatus status);

    List<Pack> findAllByOrderByCreatedAtDesc();
}
