package com.crabit.backend.wish;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SharedCardRepository extends JpaRepository<SharedCard, UUID> {

	Optional<SharedCard> findByWishId(UUID wishId);
	void deleteByWishId(UUID wishId);
}
