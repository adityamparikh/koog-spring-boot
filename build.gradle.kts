plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
}

group = "dev.aparikh"
version = "0.0.1-SNAPSHOT"
description = "koog-spring-boot"

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
