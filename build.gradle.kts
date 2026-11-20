plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    // Typed tool results: Koog's reflection ToolSet serializes a @Tool method's return value to
    // JSON for the LLM via kotlinx-serialization, so the agent-layer view DTOs need @Serializable.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    // Lint gate. Hangs off `check`, so `./gradlew build` stays the one command.
    alias(libs.plugins.detekt)
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
    // Print the assertion message, file:line and stack of a failing test on the console, not just
    // its name and a path to the HTML report.
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// detekt: detekt's default rule set plus the overrides in config/detekt/detekt.yml. No baseline —
// the code is clean against this config, so any new finding fails `check`.
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
}

// detekt runs on its own embedded Kotlin compiler and refuses any other kotlin-* version on its
// classpath, but the Spring dependency-management plugin pins every configuration's Kotlin to the
// project's. Give the detekt tool configuration back the version detekt was built with.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(dev.detekt.gradle.plugin.getSupportedKotlinVersion())
        }
    }
}

