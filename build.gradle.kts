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
	implementation(enforcedPlatform("com.google.cloud:libraries-bom:26.86.0"))
	implementation("com.google.cloud:google-cloud-storage")
	implementation("com.google.cloud:google-cloud-vision")
	implementation("com.google.cloud:google-cloud-iamcredentials")
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
	exclude("META-INF/crabit/openapi/openapi.yaml")
	from("api/openapi.yaml") {
		into("BOOT-INF/classes/META-INF/crabit/openapi")
	}
}

// Non-web tooling is deliberately absent from main output and bootJar.
val simulation by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[simulation.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[simulation.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
val simulationTests by sourceSets.creating {
    java.setSrcDirs(listOf("src/simulation/test"))
    compileClasspath += simulation.output + sourceSets.main.get().output
    runtimeClasspath += simulation.output + sourceSets.main.get().output
}
configurations[simulationTests.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[simulationTests.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.register<Test>("simulationTest") {
    description = "Tests isolated simulation tooling and disposable local database replay."
    testClassesDirs = simulationTests.output.classesDirs
    classpath = simulationTests.runtimeClasspath
    useJUnitPlatform()
}
tasks.register<JavaExec>("simulationInspect") {
    description = "Read-only bundle manifest admission; never asserts domain validity or application readiness."
    classpath = simulation.runtimeClasspath
    mainClass.set("com.crabit.backend.simulation.SimulationBundleInspect")
}

tasks.register<JavaExec>("simulationCashCheck") {
    description = "Read-only cash projection verification; not full dataset validation or application."
    classpath = simulation.runtimeClasspath
    mainClass.set("com.crabit.backend.simulation.SimulationCashCheck")
}

dependencies {
    add(simulation.implementationConfigurationName, "org.testcontainers:testcontainers-postgresql")
}

val simulationClockImage by tasks.registering(Exec::class) {
    description = "Builds only the local disposable PostgreSQL clock image; no push or deployment."
    commandLine("docker", "build", "-t", "crabit-simulation-postgres:clock-v1", "src/simulation/docker")
}
tasks.named("simulationTest") { dependsOn(simulationClockImage) }
tasks.register<JavaExec>("simulationClockCheck") {
    description = "Checks actual PostgreSQL and Java logical clocks in a new disposable local DB."
    dependsOn(simulationClockImage)
    classpath = simulation.runtimeClasspath
    mainClass.set("com.crabit.backend.simulation.SimulationClockCheck")
}

tasks.register<JavaExec>("simulationRun") {
    description = "Replays supported bundle commands in a fresh local DB and records raw evidence; no import or full validation."
    dependsOn(simulationClockImage)
    classpath = simulation.runtimeClasspath
    mainClass.set("com.crabit.backend.simulation.SimulationReplayRun")
}

tasks.register<JavaExec>("simulationSession") {
    description = "Persistent local policy session: actual step/result feedback, then the shared replay exports."
    dependsOn(simulationClockImage)
    classpath = simulation.runtimeClasspath
    mainClass.set("com.crabit.backend.simulation.SimulationReplaySession")
    standardInput = System.`in`
}

tasks.register<JavaExec>("simulationImport") {
    description = "Explicit non-web management process: prepare/dry-run, single apply, read-back, restore."
    classpath = simulation.runtimeClasspath
    mainClass.set("com.crabit.backend.simulation.SimulationImportCli")
}
