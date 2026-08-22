package com.crabit.backend.relationship;

import java.util.Map;

public final class RelationshipException extends RuntimeException {

	private final Code code;
	private final String field;

	public RelationshipException(Code code, String message) {
		this(code, message, null);
	}

	public RelationshipException(Code code, String message, String field) {
		super(message);
		this.code = code;
		this.field = field;
	}

	public Code code() { return code; }
	public String field() { return field; }
	public Map<String, Object> details() { return Map.of(); }

	public enum Code {
		MALFORMED_REQUEST,
		AUTH_REQUIRED,
		FORBIDDEN,
		ACADEMY_NOT_FOUND,
		STUDENT_NOT_FOUND,
		FRIENDSHIP_NOT_FOUND,
		FRIEND_REQUEST_NOT_FOUND,
		STUDENT_BLOCK_NOT_FOUND,
		SELF_RELATIONSHIP,
		ALREADY_FRIENDS,
		FRIEND_REQUEST_ALREADY_PENDING,
		INCOMING_FRIEND_REQUEST_PENDING,
		FRIEND_REQUEST_NOT_PENDING,
		FRIEND_REQUEST_NOT_ACTIONABLE,
		STUDENT_BLOCK_ALREADY_ACTIVE
	}
}
