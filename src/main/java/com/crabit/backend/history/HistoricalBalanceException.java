package com.crabit.backend.history;

/** Public, sanitized historical-query failures; storage details never enter the wire message. */
public final class HistoricalBalanceException extends RuntimeException {
    public enum Code {
        MALFORMED_REQUEST(400, false, "The historical balance request is malformed."),
        AUTH_REQUIRED(401, false, "A valid machine credential is required."),
        CARD_BALANCE_ACCOUNT_NOT_FOUND(404, false, "Card Balance Account was not found."),
        HISTORICAL_BALANCE_INTEGRITY_ERROR(500, false, "Historical balance integrity could not be verified."),
        HISTORICAL_BALANCE_QUERY_UNAVAILABLE(503, true, "Historical balance is temporarily unavailable.");

        public final int status;
        public final boolean retryable;
        public final String message;
        Code(int status, boolean retryable, String message) {
            this.status = status; this.retryable = retryable; this.message = message;
        }
    }
    private final Code code;
    public HistoricalBalanceException(Code code) { super(code.message); this.code = code; }
    public Code code() { return code; }
    public static HistoricalBalanceException malformed() { return new HistoricalBalanceException(Code.MALFORMED_REQUEST); }
    public static HistoricalBalanceException integrity() { return new HistoricalBalanceException(Code.HISTORICAL_BALANCE_INTEGRITY_ERROR); }
}
