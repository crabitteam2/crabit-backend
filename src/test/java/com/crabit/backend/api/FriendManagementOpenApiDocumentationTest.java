package com.crabit.backend.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.yaml.snakeyaml.Yaml;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:friend-management-openapi;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.jpa.hibernate.ddl-auto=none",
	"spring.flyway.enabled=false",
	"spring.main.banner-mode=off",
	"logging.level.root=warn",
	"crabit.documentation.enabled=true"
})
@AutoConfigureMockMvc
class FriendManagementOpenApiDocumentationTest {

	private static final String STUDENTS = "/v1/academies/{academyId}/students";
	private static final String FRIENDS = "/v1/academies/{academyId}/friends";
	private static final String FRIEND = FRIENDS + "/{studentId}";
	private static final String REQUESTS = "/v1/academies/{academyId}/friend-requests";
	private static final String SENT_REQUESTS = REQUESTS + "/sent";
	private static final String RECEIVED_REQUESTS = REQUESTS + "/received";
	private static final String REQUEST = REQUESTS + "/{friendRequestId}";
	private static final String ACCEPTANCE = REQUEST + "/acceptance";
	private static final String REJECTION = REQUEST + "/rejection";
	private static final String BLOCKS = "/v1/me/student-blocks";
	private static final String BLOCK = BLOCKS + "/{studentId}";

	private static Map<String, Object> document;

	@Autowired
	private MockMvc mockMvc;

	@BeforeAll
	static void parseContract() throws IOException {
		try (InputStream input = Files.newInputStream(Path.of("api", "openapi.yaml"))) {
			document = map(new Yaml().load(input));
		}
	}

	@Test
	void materializesAllTwelveOperationsWithExactSecurityAndStatusInventories() {
		Map<String, ExpectedOperation> expected = expectedOperations();

		expected.forEach((operationId, expectedOperation) -> {
			Map<String, Object> actual = operation(expectedOperation.path(), expectedOperation.method().toLowerCase());
			assertThat(actual).containsEntry("operationId", operationId)
					.containsEntry("tags", List.of("Friend Management"))
					.containsEntry("security", List.of(Map.of("SyntheticBearer", List.of())));
			assertThat(map(actual.get("responses")).keySet())
					.as(operationId + " statuses")
					.containsExactlyInAnyOrderElementsOf(expectedOperation.statuses());
		});

		for (String operationId : List.of("unfriendAcademyStudent", "unblockStudent")) {
			ExpectedOperation target = expected.get(operationId);
			Map<String, Object> noContent = map(map(operation(target.path(), target.method().toLowerCase()).get("responses")).get("204"));
			assertThat(noContent).containsOnlyKeys("description");
			assertThat(noContent.get("description").toString()).contains("본문");
		}
	}

	@Test
	void generatedSpringdocMatchesTheCompleteContractOnceProductionEndpointsExist() throws Exception {
		Map<String, Object> generated = generatedDocument();
		assumeTrue(map(generated.get("paths")).containsKey(STUDENTS),
				"Preapproval materializes executable parity assertions before production endpoints are allowed.");

		expectedOperations().forEach((operationId, expectedOperation) -> {
			Map<String, Object> actual = operation(generated, expectedOperation.path(), expectedOperation.method().toLowerCase());
			assertThat(actual).as("generated " + operationId)
					.containsEntry("operationId", operationId)
					.containsEntry("tags", List.of("Friend Management"))
					.containsEntry("security", List.of(Map.of("SyntheticBearer", List.of())));
			assertThat(map(actual.get("responses")).keySet())
					.containsExactlyInAnyOrderElementsOf(expectedOperation.statuses());
		});

		for (String schemaName : List.of(
				"RelationshipState", "FriendRequestStatus", "StudentSummary", "StudentRelationship",
				"StudentRelationshipPage", "Friend", "FriendPage", "CreateFriendRequestRequest",
				"FriendRequest", "FriendRequestPage", "CreateStudentBlockRequest", "StudentBlock",
				"StudentBlockPage")) {
			assertThat(normalizeRequiredOrder(path(generated, "components", "schemas", schemaName)))
					.as("generated component " + schemaName)
					.isEqualTo(normalizeRequiredOrder(path(document, "components", "schemas", schemaName)));
		}

		assertThat(requestSchemaRef(operation(generated, REQUESTS, "post")))
				.isEqualTo("#/components/schemas/CreateFriendRequestRequest");
		assertThat(requestSchemaRef(operation(generated, BLOCKS, "post")))
				.isEqualTo("#/components/schemas/CreateStudentBlockRequest");
		assertThat(responseSchemaRef(operation(generated, STUDENTS, "get"), "200"))
				.isEqualTo("#/components/schemas/StudentRelationshipPage");
		assertThat(responseSchemaRef(operation(generated, FRIENDS, "get"), "200"))
				.isEqualTo("#/components/schemas/FriendPage");
		assertThat(responseSchemaRef(operation(generated, SENT_REQUESTS, "get"), "200"))
				.isEqualTo("#/components/schemas/FriendRequestPage");
		assertThat(responseSchemaRef(operation(generated, BLOCKS, "get"), "200"))
				.isEqualTo("#/components/schemas/StudentBlockPage");
		assertThat(map(map(operation(generated, FRIEND, "delete").get("responses")).get("204")))
				.doesNotContainKey("content");
		assertThat(map(map(operation(generated, BLOCK, "delete").get("responses")).get("204")))
				.doesNotContainKey("content");
	}

	@Test
	void bindsActorsToCurrentPrincipalAndKeepsClientOwnershipFieldsOut() {
		for (String operationId : List.of(
				"searchAcademyStudents", "listAcademyFriends", "unfriendAcademyStudent",
				"sendFriendRequest", "listSentFriendRequests", "listReceivedFriendRequests",
				"cancelFriendRequest", "acceptFriendRequest", "rejectFriendRequest",
				"listMyStudentBlocks", "blockStudent", "unblockStudent")) {
			assertThat(parameterNames(operationById(operationId)))
					.as(operationId + " client-controlled ownership")
					.doesNotContain("actorStudentId", "senderId", "receiverOwnerId", "blockerId", "accountOwnerId",
							"Idempotency-Key");
		}

		assertThat(properties(schema("CreateFriendRequestRequest"))).containsOnlyKeys("studentId");
		assertThat(properties(schema("CreateStudentBlockRequest"))).containsOnlyKeys("studentId");
		assertThat(schema("CreateFriendRequestRequest").get("description").toString())
				.contains("수신자", "CurrentPrincipal.subjectId");
		assertThat(schema("CreateStudentBlockRequest").get("description").toString())
				.contains("차단된 학생", "CurrentPrincipal.subjectId");
		assertThat(operation(REQUESTS, "post").get("description").toString())
				.contains("CurrentPrincipal.subjectId", "Idempotency-Key는 허용되지 않습니다");
		assertThat(operation(BLOCKS, "post").get("description").toString())
				.contains("클라이언트 입력은 차단기를 제어하지 않습니다", "Idempotency-Key는 허용되지 않습니다");
	}

	@Test
	void documentsNicknameNormalizationBilateralBlockExclusionAndRelationshipStates() {
		Map<String, Object> nickname = parameter(operation(STUDENTS, "get"), "nickname");
		assertThat(nickname).containsEntry("in", "query").containsEntry("required", true);
		assertThat(nickname.get("description").toString()).contains(
				"유니코드 Space_Separator", "NFC", "Cc, Cf, Zl 및 Zp", "1~80",
				"대소문자를 구분하는 연속 유니코드 코드 포인트 하위 문자열");

		String description = operation(STUDENTS, "get").get("description").toString();
		assertThat(description).contains(
				"인증된 학생", "비회원", "양방향 활성 블록",
				"닉네임 ASC", "studentId ASC", "정규화된 닉네임 필터",
				"부분 페이지 없이", "유효한 제한");
		assertThat(list(schema("RelationshipState").get("enum")))
				.containsExactly("NONE", "FRIEND", "OUTGOING_PENDING", "INCOMING_PENDING");
	}

	@Test
	void keepsEveryProjectionPrivacyMinimalAndEveryPageCursorBoundToItsOrdering() {
		Map<String, Set<String>> exactProperties = Map.ofEntries(
				Map.entry("StudentSummary", Set.of("studentId", "nickname")),
				Map.entry("StudentRelationship", Set.of("studentId", "nickname", "relationshipState")),
				Map.entry("StudentRelationshipPage", Set.of("items", "nextCursor")),
				Map.entry("Friend", Set.of("studentId", "nickname", "friendsSince")),
				Map.entry("FriendPage", Set.of("items", "nextCursor")),
				Map.entry("CreateFriendRequestRequest", Set.of("studentId")),
				Map.entry("FriendRequest", Set.of("friendRequestId", "counterpart", "status", "createdAt", "processedAt")),
				Map.entry("FriendRequestPage", Set.of("items", "nextCursor")),
				Map.entry("CreateStudentBlockRequest", Set.of("studentId")),
				Map.entry("StudentBlock", Set.of("studentId", "nickname", "blockedAt")),
				Map.entry("StudentBlockPage", Set.of("items", "nextCursor")));
		exactProperties.forEach((schemaName, fields) -> {
			Map<String, Object> target = schema(schemaName);
			assertThat(target).as(schemaName).containsEntry("additionalProperties", false);
			assertThat(properties(target).keySet()).containsExactlyInAnyOrderElementsOf(fields);
			assertThat(list(target.get("required"))).containsExactlyInAnyOrderElementsOf(fields);
		});

		assertPage("StudentRelationshipPage", "nickname, studentId");
		assertPage("FriendPage", "friendsSince, studentId");
		assertPage("FriendRequestPage", "createdAt, friendRequestId");
		assertPage("StudentBlockPage", "blockedAt, studentId");

		assertThat(properties(schema("StudentSummary")).keySet()).doesNotContain(
				"realName", "card", "wish", "authentication", "academyMembership", "academyId");
		assertThat(properties(schema("FriendRequest")).keySet()).doesNotContain(
				"senderId", "receiverId", "academyId", "ownerId");
	}

	@Test
	void fixesPendingAndProcessedRequestShapesAndListVisibility() {
		assertThat(list(schema("FriendRequestStatus").get("enum")))
				.containsExactly("PENDING", "ACCEPTED", "REJECTED", "CANCELED");
		Map<String, Object> processedAt = map(properties(schema("FriendRequest")).get("processedAt"));
		assertThat(list(processedAt.get("oneOf")))
				.anySatisfy(branch -> assertThat(map(branch)).containsEntry("$ref", "#/components/schemas/UtcInstant"))
				.anySatisfy(branch -> assertThat(map(branch)).containsEntry("type", "null"));
		assertThat(schema("FriendRequest").get("description").toString())
				.contains("전송된 결과의 수신자", "수신된 결과의 전송자");
		for (String path : List.of(SENT_REQUESTS, RECEIVED_REQUESTS)) {
			assertThat(operation(path, "get").get("description").toString())
					.contains("현재 PENDING 요청", "인증된 학생",
							"createdAt DESC", "friendRequestId DESC");
		}
	}

	@Test
	void documentsPrivacyNormalizedErrorsAndEveryStateConflict() {
		assertThat(errorCodes("StudentOrAcademyNotFound"))
				.containsExactly("ACADEMY_NOT_FOUND", "STUDENT_NOT_FOUND");
		assertThat(errorCodes("FriendshipOrAcademyNotFound"))
				.containsExactly("ACADEMY_NOT_FOUND", "FRIENDSHIP_NOT_FOUND");
		assertThat(errorCodes("FriendRequestOrAcademyNotFound"))
				.containsExactly("ACADEMY_NOT_FOUND", "FRIEND_REQUEST_NOT_FOUND");
		assertThat(errorCodes("FriendRequestCreateConflict")).containsExactly(
				"SELF_RELATIONSHIP", "ALREADY_FRIENDS", "FRIEND_REQUEST_ALREADY_PENDING",
				"INCOMING_FRIEND_REQUEST_PENDING");
		assertThat(errorCodes("FriendRequestAcceptanceConflict")).containsExactly(
				"FRIEND_REQUEST_NOT_PENDING", "FRIEND_REQUEST_NOT_ACTIONABLE", "ALREADY_FRIENDS");
		assertThat(errorCodes("StudentBlockConflict"))
				.containsExactly("SELF_RELATIONSHIP", "STUDENT_BLOCK_ALREADY_ACTIVE");

		Map<String, Object> examples = map(path("components", "examples"));
		assertThat(map(examples.get("StudentNotFoundCrossAcademy")).get("value"))
				.isEqualTo(map(examples.get("StudentNotFoundBlocked")).get("value"));
		assertThat(examples.keySet()).contains(
				"MalformedFriendManagementUuid", "MalformedFriendManagementNickname",
				"MalformedFriendManagementLimit", "MalformedFriendManagementCursor",
				"AuthRequiredFriendManagement", "ForbiddenFriendManagement",
				"FriendRequestNotFoundUnauthorized", "SelfRelationshipConflict",
				"AlreadyFriendsConflict", "FriendRequestAlreadyPendingConflict",
				"IncomingFriendRequestPendingConflict", "FriendRequestNotPendingConflict",
				"FriendRequestNotActionableConflict", "StudentBlockAlreadyActiveConflict");
	}

	@Test
	void documentsCanonicalPairAtomicityAndNoRestorationRules() {
		assertThat(operation(ACCEPTANCE, "post").get("description").toString()).contains(
				"표준 학생 쌍 잠금", "현재 학원 멤버십", "정확한 요청",
				"현재 우정의 부재", "방향 차단의 부재",
				"한 트랜잭션", "동시 패자");
		assertThat(operation(BLOCKS, "post").get("description").toString()).contains(
				"정식 학생 쌍 잠금", "모든 학원에서 현재의 모든 우정",
				"양방향 및 모든 학원의 모든 PENDING 요청", "CANCELED",
				"한 번의 트랜잭션");
		assertThat(operation(FRIEND, "delete").get("description").toString())
				.contains("기록된 친구 요청을 다시 활성화하지 않으며");
		assertThat(operation(BLOCK, "delete").get("description").toString())
				.contains("우정", "차단으로 인해 취소된 요청", "절대 복원되지 않으며");
	}

	private static void assertPage(String schemaName, String orderingTuple) {
		Map<String, Object> page = schema(schemaName);
		Map<String, Object> cursor = map(properties(page).get("nextCursor"));
		assertThat(list(cursor.get("type"))).containsExactly("string", "null");
		for (String field : orderingTuple.split(", ")) {
			assertThat(cursor.get("description").toString())
					.contains("nickname".equals(field) ? "닉네임" : field);
		}
		assertThat(cursor.get("description").toString()).contains("더 이상 항목이 없으면 null");
	}

	private static Object normalizeRequiredOrder(Object value) {
		if (value instanceof Map<?, ?> rawMap) {
			Map<String, Object> normalized = new java.util.TreeMap<>();
			rawMap.forEach((key, child) -> {
				String name = key.toString();
				Object normalizedChild = normalizeRequiredOrder(child);
				if ("required".equals(name) && normalizedChild instanceof List<?> required) {
					normalizedChild = required.stream().map(Object::toString).sorted().toList();
				}
				normalized.put(name, normalizedChild);
			});
			return normalized;
		}
		if (value instanceof List<?> list) {
			return list.stream().map(FriendManagementOpenApiDocumentationTest::normalizeRequiredOrder).toList();
		}
		return value;
	}

	private static ExpectedOperation operation(String method, String path, String... statuses) {
		return new ExpectedOperation(method, path, Set.of(statuses));
	}

	private static Map<String, ExpectedOperation> expectedOperations() {
		Map<String, ExpectedOperation> expected = new LinkedHashMap<>();
		expected.put("searchAcademyStudents", operation("GET", STUDENTS, "200", "400", "401", "403", "404"));
		expected.put("listAcademyFriends", operation("GET", FRIENDS, "200", "400", "401", "403", "404"));
		expected.put("unfriendAcademyStudent", operation("DELETE", FRIEND, "204", "400", "401", "403", "404"));
		expected.put("sendFriendRequest", operation("POST", REQUESTS, "201", "400", "401", "403", "404", "409"));
		expected.put("listSentFriendRequests", operation("GET", SENT_REQUESTS, "200", "400", "401", "403", "404"));
		expected.put("listReceivedFriendRequests", operation("GET", RECEIVED_REQUESTS, "200", "400", "401", "403", "404"));
		expected.put("cancelFriendRequest", operation("DELETE", REQUEST, "200", "400", "401", "403", "404", "409"));
		expected.put("acceptFriendRequest", operation("POST", ACCEPTANCE, "200", "400", "401", "403", "404", "409"));
		expected.put("rejectFriendRequest", operation("POST", REJECTION, "200", "400", "401", "403", "404", "409"));
		expected.put("listMyStudentBlocks", operation("GET", BLOCKS, "200", "400", "401", "403"));
		expected.put("blockStudent", operation("POST", BLOCKS, "201", "400", "401", "403", "404", "409"));
		expected.put("unblockStudent", operation("DELETE", BLOCK, "204", "400", "401", "403", "404"));
		return expected;
	}

	private static Map<String, Object> operation(String path, String method) {
		return map(map(path("paths", path)).get(method));
	}

	private static Map<String, Object> operation(Map<String, Object> root, String path, String method) {
		return map(map(path(root, "paths", path)).get(method));
	}

	private Map<String, Object> generatedDocument() throws Exception {
		String body = mockMvc.perform(get("/v3/api-docs"))
				.andExpect(status().isOk())
				.andReturn().getResponse().getContentAsString();
		return JsonPath.read(body, "$");
	}

	private static String requestSchemaRef(Map<String, Object> operation) {
		return map(map(map(operation.get("requestBody")).get("content")).get("application/json"))
				.values().stream().map(FriendManagementOpenApiDocumentationTest::map)
				.findFirst().orElseThrow().get("$ref").toString();
	}

	private static String responseSchemaRef(Map<String, Object> operation, String status) {
		Map<String, Object> response = map(map(operation.get("responses")).get(status));
		return map(map(map(response.get("content")).get("application/json")).get("schema"))
				.get("$ref").toString();
	}

	private static Map<String, Object> operationById(String operationId) {
		for (Object rawPath : map(document.get("paths")).values()) {
			for (Object rawOperation : map(rawPath).values()) {
				if (rawOperation instanceof Map<?, ?> && operationId.equals(map(rawOperation).get("operationId"))) {
					return map(rawOperation);
				}
			}
		}
		throw new IllegalArgumentException("Unknown operationId: " + operationId);
	}

	private static Set<String> parameterNames(Map<String, Object> operation) {
		Set<String> names = new LinkedHashSet<>();
		for (Object raw : list(operation.get("parameters"))) {
			Map<String, Object> parameter = map(raw);
			if (parameter.containsKey("$ref")) {
				parameter = map(resolve(parameter.get("$ref").toString()));
			}
			names.add(parameter.get("name").toString());
		}
		return names;
	}

	private static Map<String, Object> parameter(Map<String, Object> operation, String name) {
		return list(operation.get("parameters")).stream()
				.map(FriendManagementOpenApiDocumentationTest::map)
				.map(value -> value.containsKey("$ref") ? map(resolve(value.get("$ref").toString())) : value)
				.filter(value -> name.equals(value.get("name")))
				.findFirst().orElseThrow();
	}

	private static List<Object> errorCodes(String responseName) {
		return list(map(path("components", "responses", responseName)).get("x-error-codes"));
	}

	private static Map<String, Object> schema(String name) {
		return map(path("components", "schemas", name));
	}

	private static Map<String, Object> properties(Map<String, Object> schema) {
		return map(schema.get("properties"));
	}

	private static Object path(String... segments) {
		return path(document, segments);
	}

	private static Object path(Map<String, Object> root, String... segments) {
		Object current = root;
		for (String segment : segments) {
			current = map(current).get(segment);
		}
		return current;
	}

	private static Object resolve(String ref) {
		Object current = document;
		for (String encoded : ref.substring(2).split("/")) {
			String segment = encoded.replace("~1", "/").replace("~0", "~");
			current = map(current).get(segment);
		}
		return current;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> map(Object value) {
		return value == null ? Map.of() : (Map<String, Object>) value;
	}

	@SuppressWarnings("unchecked")
	private static List<Object> list(Object value) {
		return value == null ? List.of() : (List<Object>) value;
	}

	private record ExpectedOperation(String method, String path, Set<String> statuses) {}
}
