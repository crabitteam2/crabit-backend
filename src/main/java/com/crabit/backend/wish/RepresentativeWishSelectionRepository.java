package com.crabit.backend.wish;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RepresentativeWishSelectionRepository
		extends JpaRepository<RepresentativeWishSelection, UUID> {
}
