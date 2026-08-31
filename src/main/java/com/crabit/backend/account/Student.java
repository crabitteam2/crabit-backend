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

	@Column(name = "age", nullable = false)
	private int age;

	protected Student() {
	}

	public Student(UUID id, String nickname, int age) {
		this.id = Objects.requireNonNull(id, "id");
		if (nickname == null || nickname.isBlank()) {
			throw new IllegalArgumentException("Student nickname must not be blank");
		}
		if (age < 0 || age > 120) {
			throw new IllegalArgumentException("Student age must be between 0 and 120");
		}
		this.nickname = nickname;
		this.age = age;
	}

	public UUID id() { return id; }
	public String nickname() { return nickname; }
	public int age() { return age; }
}
