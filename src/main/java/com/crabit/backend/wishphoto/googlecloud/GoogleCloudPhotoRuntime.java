package com.crabit.backend.wishphoto.googlecloud;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.api.gax.retrying.RetrySettings;
import com.google.auth.ServiceAccountSigner;
import com.google.cloud.http.HttpTransportOptions;
import com.google.cloud.iam.credentials.v1.*;
import com.google.cloud.storage.*;
import com.google.cloud.vision.v1.*;
import com.google.protobuf.ByteString;
import java.time.Duration;

public final class GoogleCloudPhotoRuntime implements AutoCloseable {
	@FunctionalInterface
	interface CredentialsFactory { GoogleMetadataCredentials create() throws java.io.IOException; }
	private final Storage storage;
	private final IamCredentialsClient iam;
	private final ImageAnnotatorClient vision;
	private final GoogleCloudWishPhotoStorage photos;
	private final GoogleCloudWishPhotoSafetyScanner safety;
	private final GoogleMetadataCredentials credentials;
	public GoogleCloudPhotoRuntime(GoogleCloudPhotoSettings config, WishPhotoClock clock) {
		this(config, clock, () -> new GoogleMetadataCredentials(config));
	}
	GoogleCloudPhotoRuntime(GoogleCloudPhotoSettings config, WishPhotoClock clock, CredentialsFactory credentialsFactory) {
		IamCredentialsClient createdIam = null;
		ImageAnnotatorClient createdVision = null;
		GoogleMetadataCredentials createdCredentials = null;
		Storage createdStorage = null;
		try {
			createdCredentials = credentialsFactory.create();
			credentials = createdCredentials;
			var provider = FixedCredentialsProvider.create(credentials);
			var retry = RetrySettings.newBuilder().setMaxAttempts(1)
					.setInitialRpcTimeoutDuration(Duration.ofSeconds(2)).setMaxRpcTimeoutDuration(Duration.ofSeconds(2))
					.setTotalTimeoutDuration(Duration.ofSeconds(2)).build();
			var iamSettings = IamCredentialsSettings.newBuilder().setCredentialsProvider(provider);
			iamSettings.signBlobSettings().setRetryableCodes().setRetrySettings(retry);
			createdIam = IamCredentialsClient.create(iamSettings.build());
			var visionSettings = ImageAnnotatorSettings.newBuilder().setCredentialsProvider(provider);
			visionSettings.batchAnnotateImagesSettings().setRetryableCodes().setRetrySettings(retry);
			createdVision = ImageAnnotatorClient.create(visionSettings.build());
			createdStorage = StorageOptions.newBuilder().setProjectId(config.projectId()).setCredentials(credentials)
					.setRetrySettings(retry).setTransportOptions(HttpTransportOptions.newBuilder()
							.setConnectTimeout(1000).setReadTimeout(2000).build()).build().getService();
			storage = createdStorage; iam = createdIam; vision = createdVision;
			ServiceAccountSigner signer = new ServiceAccountSigner() {
				@Override public String getAccount() { return config.serviceAccount(); }
				@Override public byte[] sign(byte[] value) {
					byte[] signature = iam.signBlob(SignBlobRequest.newBuilder().setName("projects/-/serviceAccounts/" + config.serviceAccount())
							.setPayload(ByteString.copyFrom(value)).build()).getSignedBlob().toByteArray();
					if (signature.length == 0) throw new IllegalStateException("Missing signature");
					return signature;
				}
			};
			photos = new GoogleCloudWishPhotoStorage(storage, config.bucket(), config.projectId(), signer, clock.value());
			safety = new GoogleCloudWishPhotoSafetyScanner(request -> vision.batchAnnotateImages(request));
		} catch (Exception exception) {
			if (createdVision != null) createdVision.close();
			if (createdIam != null) createdIam.close();
			if (createdStorage != null) try { createdStorage.close(); } catch (Exception ignored) { }
			if (createdCredentials != null) createdCredentials.close();
			throw new IllegalStateException("Wish photo Google Cloud runtime initialization failed");
		}
	}
	public GoogleCloudWishPhotoStorage storage() { return photos; }
	public GoogleCloudWishPhotoSafetyScanner safety() { return safety; }
	@Override public void close() throws Exception {
		try { vision.close(); } finally { try { iam.close(); } finally { try { storage.close(); } finally { credentials.close(); } } }
	}
}
