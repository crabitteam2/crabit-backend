package com.crabit.backend.recap;

final class RecapTransportException extends RuntimeException {
	private final boolean retryable; private final String code;
	RecapTransportException(String code, boolean retryable) { super(code); this.code=code; this.retryable=retryable; }
	boolean retryable() { return retryable; } String code() { return code; }
}
