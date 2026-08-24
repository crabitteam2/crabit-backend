import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
	java
	jacoco
	id("org.springframework.boot") version "4.1.0"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.crabit"
version = "0.0.1-SNAPSHOT"
description = "Crabit Spring Boot backend"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
	runtimeOnly("org.postgresql:postgresql")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testRuntimeOnly("com.h2database:h2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val jacocoReportRequested = gradle.startParameter.taskNames.any {
	it.substringAfterLast(':') == "jacocoTestReport"
}

tasks.test {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	if (jacocoReportRequested) {
		dependsOn(tasks.test)
	}
	mustRunAfter(tasks.test)

	reports {
		html.required = true
		xml.required = true
	}
}

tasks.withType<AbstractArchiveTask>().configureEach {
	isPreserveFileTimestamps = false
	isReproducibleFileOrder = true
}

tasks.processResources {
	from("api/openapi.yaml") {
		into("META-INF/crabit/openapi")
	}
}

tasks.bootJar {
	from("api/openapi.yaml") {
		into("BOOT-INF/classes/META-INF/crabit/openapi")
	}
}
