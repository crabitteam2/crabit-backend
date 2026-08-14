package com.crabit.backend.wish;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StudentRepository extends JpaRepository<Student, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select student from Student student where student.id = :studentId")
	Optional<Student> lockById(@Param("studentId") UUID studentId);
}
