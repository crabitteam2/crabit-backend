package com.crabit.backend.api;

import static com.crabit.backend.config.SwaggerUiConfiguration.SYNTHETIC_BEARER;
import static com.crabit.backend.config.SwaggerUiConfiguration.WISH_TAG;

import com.crabit.backend.auth.CurrentPrincipal;
import com.crabit.backend.wish.WishLifecycleException;
import com.crabit.backend.wishphoto.WishPhotoException;
import com.crabit.backend.wishphoto.WishPhotoService;
import com.crabit.backend.wishphoto.WishPhotoView;
import jakarta.servlet.http.HttpServletRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/v1/wish-photos")
@Tag(name = WISH_TAG)
public class WishPhotoController {
	private final WishPhotoService photos;
	public WishPhotoController(WishPhotoService photos) { this.photos = photos; }

	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@Operation(operationId = "uploadWishPhoto", summary = "Upload a Wish photo",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	public ResponseEntity<WishPhotoView> upload(
			@Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true)
			@RequestHeader(name = "Idempotency-Key", required = false) String key,
			@RequestPart("photo") MultipartFile photo, HttpServletRequest request) throws Exception {
		boolean exactlyOnePhoto = request instanceof MultipartHttpServletRequest multipart
				&& multipart.getMultiFileMap().size() == 1
				&& multipart.getMultiFileMap().containsKey("photo")
				&& multipart.getMultiFileMap().get("photo").size() == 1
				&& multipart.getParameterMap().isEmpty();
		if (!exactlyOnePhoto || photo.isEmpty()) throw new WishPhotoException(
				WishPhotoException.Code.MALFORMED_REQUEST, "Exactly one photo part is required.");
		var outcome = photos.upload(principal(request).subjectId(), key, photo.getBytes(), photo.getContentType());
		return ResponseEntity.status(201).cacheControl(CacheControl.noStore())
				.header("Idempotency-Replayed", outcome.replayed() ? "true" : "false")
				.body(outcome.photo());
	}

	@DeleteMapping("/{photoId}")
	@Operation(operationId = "deletePendingWishPhoto", summary = "Delete a Pending Wish photo",
			security = @SecurityRequirement(name = SYNTHETIC_BEARER))
	public ResponseEntity<Void> cancel(@PathVariable UUID photoId, HttpServletRequest request) {
		photos.cancel(principal(request).subjectId(), photoId);
		return ResponseEntity.noContent().build();
	}

	private static CurrentPrincipal principal(HttpServletRequest request) {
		Object value = request.getAttribute(CurrentPrincipal.REQUEST_ATTRIBUTE);
		if (value instanceof CurrentPrincipal principal && principal.role() == CurrentPrincipal.Role.STUDENT) return principal;
		throw new WishLifecycleException(WishLifecycleException.Code.AUTH_REQUIRED,
				"A known Bearer token is required.");
	}
}
