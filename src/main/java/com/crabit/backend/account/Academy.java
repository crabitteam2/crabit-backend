package com.crabit.backend.account;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "academy")
public class Academy {

	@Id
	private UUID id;

	@Column(name = "name", nullable = false, length = 120)
	private String name;

	protected Academy() {
	}

	public Academy(UUID id, String name) {
		this.id = Objects.requireNonNull(id, "id");
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Academy name must not be blank");
		}
		this.name = name;
	}

	public UUID id() { return id; }
	public String name() { return name; }
}
