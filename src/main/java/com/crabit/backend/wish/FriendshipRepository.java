package com.crabit.backend.wish;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FriendshipRepository extends JpaRepository<Friendship, UUID> {

	boolean existsByAcademyIdAndStudentLowIdAndStudentHighIdAndEndedAtIsNull(
			UUID academyId, UUID studentLowId, UUID studentHighId);
}
