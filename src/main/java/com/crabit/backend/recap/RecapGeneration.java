package com.crabit.backend.recap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "recap_generation")
public class RecapGeneration {
	@Id private UUID id;
	@Column(name = "account_id", nullable = false, updatable = false) private UUID accountId;
	@Column(name = "student_id", nullable = false, updatable = false) private UUID studentId;
	@Column(name = "academy_id", nullable = false, updatable = false) private UUID academyId;
	@Enumerated(EnumType.STRING) @Column(nullable = false, updatable = false, length = 16)
	private RecapKind kind;
	@Column(name = "period_start", nullable = false, updatable = false) private LocalDate periodStart;
	@Column(name = "period_end_exclusive", nullable = false, updatable = false)
	private LocalDate periodEndExclusive;
	@Column(name = "schema_version", nullable = false, updatable = false) private int schemaVersion;
	@Column(name = "algorithm_version", nullable = false, updatable = false, length = 32)
	private String algorithmVersion;
	@Column(name = "generation_version", nullable = false, updatable = false) private long generationVersion;
	@Column(name = "input_digest", nullable = false, updatable = false, length = 71) private String inputDigest;
	@Column(name = "request_json", nullable = false, updatable = false, columnDefinition = "text")
	private String requestJson;
	@Column(name = "view_json", columnDefinition = "text") private String viewJson;
	@Column(name = "internal_metrics_json", columnDefinition = "text") private String internalMetricsJson;
	@Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private RecapGenerationState state;
	@Column(name = "attempt_count", nullable = false) private int attemptCount;
	@Column(name = "next_attempt_at") private Instant nextAttemptAt;
	@Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
	@Column(name = "started_at") private Instant startedAt;
	@Column(name = "generated_at") private Instant generatedAt;
	@Column(name = "failed_at") private Instant failedAt;
	@Column(name = "error_code", length = 80) private String errorCode;
	@Column(name = "error_retryable") private Boolean errorRetryable;
	@Column(name = "current_version", nullable = false) private boolean currentVersion;

	protected RecapGeneration() {}

	public RecapGeneration(UUID id, UUID accountId, UUID studentId, UUID academyId,
			RecapKind kind, LocalDate periodStart, LocalDate periodEndExclusive,
			long generationVersion, String inputDigest, String requestJson, Instant createdAt) {
		this.id = Objects.requireNonNull(id);
		this.accountId = Objects.requireNonNull(accountId);
		this.studentId = Objects.requireNonNull(studentId);
		this.academyId = Objects.requireNonNull(academyId);
		this.kind = Objects.requireNonNull(kind);
		this.periodStart = Objects.requireNonNull(periodStart);
		this.periodEndExclusive = Objects.requireNonNull(periodEndExclusive);
		if (!periodEndExclusive.isAfter(periodStart) || generationVersion < 1) throw new IllegalArgumentException();
		this.schemaVersion = 1;
		this.algorithmVersion = "recap-1";
		this.generationVersion = generationVersion;
		this.inputDigest = Objects.requireNonNull(inputDigest);
		this.requestJson = Objects.requireNonNull(requestJson);
		this.createdAt = Objects.requireNonNull(createdAt);
		this.state = RecapGenerationState.PENDING;
	}

	public void start(Instant now) { state = RecapGenerationState.RUNNING; startedAt = now; attemptCount++; nextAttemptAt = null; }
	public void succeed(String view, String metrics, Instant now) { state = RecapGenerationState.SUCCEEDED; viewJson = Objects.requireNonNull(view); internalMetricsJson = metrics; generatedAt = now; failedAt = null; errorCode = null; errorRetryable = null; }
	public void notEligible(Instant now) { state = RecapGenerationState.NOT_ELIGIBLE; viewJson = null; internalMetricsJson = null; generatedAt = now; failedAt = null; }
	public void fail(String code, boolean retryable, Instant now, Instant retryAt) { state = RecapGenerationState.FAILED; errorCode = code; errorRetryable = retryable; failedAt = now; nextAttemptAt = retryable ? retryAt : null; }
	public void makeCurrent() { currentVersion = true; }
	public void supersede() { currentVersion = false; state = RecapGenerationState.SUPERSEDED; }

	public UUID id() { return id; } public UUID accountId() { return accountId; }
	public UUID studentId() { return studentId; } public UUID academyId() { return academyId; }
	public RecapKind kind() { return kind; } public LocalDate periodStart() { return periodStart; }
	public LocalDate periodEndExclusive() { return periodEndExclusive; }
	public int schemaVersion() { return schemaVersion; } public String algorithmVersion() { return algorithmVersion; }
	public long generationVersion() { return generationVersion; } public String inputDigest() { return inputDigest; }
	public String requestJson() { return requestJson; } public String viewJson() { return viewJson; }
	public String internalMetricsJson() { return internalMetricsJson; }
	public RecapGenerationState state() { return state; } public int attemptCount() { return attemptCount; }
	public Instant nextAttemptAt() { return nextAttemptAt; } public Instant generatedAt() { return generatedAt; }
	public boolean currentVersion() { return currentVersion; }
}
