package app.llm;

/** Safe, provider-neutral invocation failure. Adapters must not attach secret-bearing SDK exceptions. */
public final class LLMFailure extends app.core.runtime.TaskFailure {
    public enum Kind { RATE_LIMIT, UNAVAILABLE, TIMEOUT, CONFIGURATION, RESPONSE, TRANSPORT, CANCELLED }
    private final Kind kind;
    private final Integer httpStatus;
    public LLMFailure(Kind kind, Integer httpStatus, String safeDetail) {
        super(kind + (httpStatus == null ? "" : " (HTTP " + httpStatus + ")") + ": " + safeDetail);
        this.kind = kind; this.httpStatus = httpStatus;
    }
    public Kind kind() { return kind; }
    public Integer httpStatus() { return httpStatus; }
    public boolean transientFailure() { return kind == Kind.RATE_LIMIT || kind == Kind.UNAVAILABLE || kind == Kind.TIMEOUT || kind == Kind.TRANSPORT; }
    public static LLMFailure configuration(String detail) { return new LLMFailure(Kind.CONFIGURATION, null, detail); }
    public static LLMFailure response(String detail) { return new LLMFailure(Kind.RESPONSE, null, detail); }
}
