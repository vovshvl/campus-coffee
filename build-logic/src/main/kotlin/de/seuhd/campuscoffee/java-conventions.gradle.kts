package de.seuhd.campuscoffee

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.process.CommandLineArgumentProvider

// Shared configuration for the library/application modules: the Java toolchain (also used by the
// Kotlin compilation), the Spring Boot BOM, and the test JVM args. JaCoCo's agent is added by
// campuscoffee.jacoco-conventions. The Java major version is sourced from the version catalog
// (libs `java`) so it cannot drift from the Kotlin target, mise, and the Docker image.
plugins {
    `java-library`
    id("io.spring.dependency-management")
}

val libs = the<VersionCatalogsExtension>().named("libs")
val javaVersion = libs.findVersion("java").get().requiredVersion

group = "de.seuhd.campuscoffee"
// 0.1.x line for the Gradle/Kotlin migration, distinct from the 0.0.x Maven/Java line on main.
version = "0.1.0"

// Align the Kotlin stdlib/reflect with the Kotlin plugin version (Boot 4 manages an older stdlib;
// the plugin needs >= 2.3 for jvmTarget 25).
extra["kotlin.version"] = libs.findVersion("kotlin").get().requiredVersion

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(javaVersion))
    }
}

repositories {
    mavenCentral()
}

// Retain method parameter names so Spring can bind @PathVariable/@RequestParam by name; without
// this, such requests fail with HTTP 400.
tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.add("-parameters")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.findVersion("spring-boot").get().requiredVersion}")
    }
}

dependencies {
    // Test stack shared by every module: JUnit 5 / Mockito / AssertJ via the starter, plus the
    // JUnit Platform launcher Gradle needs on the test runtime classpath (the Spring Boot BOM
    // manages its version but does not add the dependency). Production dependencies that only some
    // modules use (e.g., spring-boot-starter-web) live in those modules' build files.
    // Force a single JUnit Platform version: cucumber-junit-platform-engine pulls JUnit Platform
    // 1.x transitively, which clashes with the JUnit 6 that Spring 7 requires.
    testImplementation(enforcedPlatform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("spring-boot-starter-test").get())
    testImplementation(libs.findLibrary("mockito-kotlin").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())
}

// Mockito's agent is the mockito-core jar itself; resolve just that jar on a dedicated
// non-transitive configuration to pass as -javaagent.
val mockitoAgent = configurations.create("mockitoAgent") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    "mockitoAgent"(libs.findLibrary("mockito-core").get())
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-javaagent:${mockitoAgent.singleFile.absolutePath}")
    })
}
