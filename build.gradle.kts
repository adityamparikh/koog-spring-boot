plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    // Typed tool results: Koog's reflection ToolSet serializes a @Tool method's return value to
    // JSON for the LLM via kotlinx-serialization, so the agent-layer view DTOs need @Serializable.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "dev.aparikh"
version = "0.0.1-SNAPSHOT"
description = "koog-spring-boot"

// Override the Spring Boot BOM's kotlin-serialization version (1.6.3) up to what Koog 1.0.0 needs.
// Koog's JDBC persistence providers serialize Message via kotlin.time.Instant →
// kotlinx.serialization.internal.InstantSerializer, which only exists from 1.9.0. The Spring
// dependency-management plugin honours this `extra` property when resolving the managed BOM.
extra["kotlin-serialization.version"] = "1.9.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Web + persistence (Spring Data JDBC) + actuator.
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jdbc)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.jackson.module.kotlin)
    implementation(kotlin("reflect"))
    implementation(libs.springdoc.openapi.starter.webmvc.ui)

    // Koog agentic framework (step 2+): the Spring Boot starter auto-configures the LLM
    // executors; koog-agents (AIAgent, models, prompt executors) comes in transitively.
    implementation(libs.koog.spring.boot.starter)

    // Koog persistence (step 5): DataSource-backed JDBC providers for the ChatMemory transcript
    // store and the Persistence checkpoint store. Optional modules, not transitive via koog-agents.
    implementation(libs.koog.agents.features.chat.history.jdbc)
    implementation(libs.koog.agents.features.persistence.jdbc)

    // Observability (step 6): OTLP exporter for shipping Koog agent spans to grafana/otel-lgtm.
    // The OTel BOM (aligned to Koog's OTel SDK version) governs all opentelemetry-* versions.
    implementation(platform(libs.opentelemetry.bom))
    implementation(libs.opentelemetry.exporter.otlp)
    testImplementation(libs.opentelemetry.sdk.testing)

    // Database driver + Flyway migrations.
    runtimeOnly(libs.postgresql)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)

    // Local Postgres via Spring Boot Docker Compose support (dev/run only).
    developmentOnly(libs.spring.boot.docker.compose)

    // Testing: unit (MockK) + integration (Testcontainers Postgres).
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.mockk)
    // Koog agent testing: deterministic mock LLM executors (no live API calls in tests).
    testImplementation(libs.koog.agents.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
