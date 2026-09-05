package com.crabit.backend.recap;

public final class RecapException extends RuntimeException {
	private final Code code;
	public RecapException(Code code, String message) { super(message); this.code = code; }
	public Code code() { return code; }
	public enum Code { MALFORMED_REQUEST, RECAP_QUERY_UNAVAILABLE }
}
