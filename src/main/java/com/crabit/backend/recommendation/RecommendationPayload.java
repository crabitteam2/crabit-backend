package com.crabit.backend.recommendation;

import java.util.List;
import java.util.UUID;

record RecommendationPayload(
		int schema_version,
		int synthetic_feature_version,
		UUID handoff_id,
		String snapshot_at,
		boolean viewer_wishes_truncated,
		boolean candidates_truncated,
		AcademyPayload academy,
		PersonPayload viewer,
		CardAccountPayload card_account,
		List<ViewerWishItemPayload> viewer_wishes,
		List<CandidatePayload> candidates) {

	RecommendationPayload {
		viewer_wishes = List.copyOf(viewer_wishes);
		candidates = List.copyOf(candidates);
	}
}

record AcademyPayload(
		UUID academy_id,
		String name,
		String address,
		String target_group,
		String category,
		String scale) {
}

record PersonPayload(UUID user_id, String name, int age) {
}

record CardAccountPayload(
		UUID account_id,
		UUID user_id,
		UUID academy_id,
		String created_at,
		String closed_at) {
}

record ViewerWishItemPayload(WishPayload wish, SavingsSummaryPayload savings_summary) {
}

record WishPayload(
		UUID wish_id,
		UUID academy_id,
		UUID account_id,
		String title,
		long target_amount,
		String target_date,
		boolean is_representative,
		String status,
		String created_at,
		String closed_at,
		long saved_amount) {
}

record CandidatePayload(
		PersonPayload owner,
		CardAccountPayload card_account,
		CandidateWishPayload wish,
		SharedCardPayload shared_card,
		SavingsSummaryPayload savings_summary) {
}

record CandidateWishPayload(
		UUID wish_id,
		UUID academy_id,
		UUID account_id,
		String title,
		long target_amount,
		String target_date,
		String status,
		String created_at,
		String closed_at,
		long saved_amount) {
}

record SharedCardPayload(
		UUID feed_id,
		UUID account_id,
		UUID wish_id,
		String kind,
		String updated_at) {
}

record SavingsSummaryPayload(
		long transaction_count,
		long total_inflow_amount,
		long total_outflow_amount,
		String last_transaction_at) {

	static SavingsSummaryPayload empty() {
		return new SavingsSummaryPayload(0, 0, 0, null);
	}
}
