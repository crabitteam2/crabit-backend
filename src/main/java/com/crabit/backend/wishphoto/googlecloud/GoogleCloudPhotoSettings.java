package com.crabit.backend.wishphoto.googlecloud;

/** Non-secret, environment-bound runtime configuration. */
public record GoogleCloudPhotoSettings(String environment, String projectId,
		String projectNumber, String bucket, String serviceAccount) {
	public GoogleCloudPhotoSettings {
		if (!("staging".equals(environment) || "stable-demo".equals(environment))
				|| !"project-9ee29576-dd79-4a1c-a70".equals(projectId)
				|| !"182907578804".equals(projectNumber)
				|| !("crabit-wish-photo-" + environment + "-" + projectNumber).equals(bucket)
				|| !("crabit-" + environment + "-runtime@" + projectId + ".iam.gserviceaccount.com").equals(serviceAccount)) {
			throw new IllegalStateException("Invalid Wish photo Google Cloud environment binding");
		}
	}
}
