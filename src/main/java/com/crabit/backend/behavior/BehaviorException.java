package com.crabit.backend.behavior;

public final class BehaviorException extends RuntimeException {
    private final String code;
    private final int status;

    public BehaviorException(String code, int status) {
        super(
                switch (code) {
                    case "MALFORMED_REQUEST" -> "The request is malformed.";
                    case "SELF_PROFILE_VISIT" -> "Self profile visits are not collected.";
                    case "EVENT_TIME_OUT_OF_RANGE" ->
                            "Event occurrence time is outside the accepted interval.";
                    case "FEED_CONTEXT_EXPIRED" -> "Feed result context has expired.";
                    default ->
                            status == 404
                                    ? "Requested resource not found."
                                    : "Event identity conflicts with a retained record.";
                });
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }

    public static BehaviorException malformed() {
        return new BehaviorException("MALFORMED_REQUEST", 400);
    }
}
