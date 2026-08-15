package com.crabit.backend.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "student")
public class Student {

	@Id
	private UUID id;

	@Column(name = "nickname", nullable = false, length = 80)
	private String nickname;

	protected Student() {
	}

	public Student(UUID id, String nickname) {
		this.id = Objects.requireNonNull(id, "id");
		if (nickname == null || nickname.isBlank()) {
			throw new IllegalArgumentException("Student nickname must not be blank");
		}
		this.nickname = nickname;
	}

	public UUID id() { return id; }
	public String nickname() { return nickname; }
}
