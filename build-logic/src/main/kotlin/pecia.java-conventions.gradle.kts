import java.nio.file.Path

plugins {
    java
    id("com.diffplug.spotless")
}

group = "dev.eyuppastirmaci"
version = "0.1.0-SNAPSHOT"

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        // Packaged offline tests install a SecurityManager, which JDK 24 and later no longer support.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    // Pecia never uses Java serialization; exceptions stay inside the running process.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

spotless {
    java {
        // Resources, golden fixtures and the tokenizer vocabulary are never formatted.
        target("src/main/java/**/*.java", "src/test/java/**/*.java", "src/testFixtures/java/**/*.java")
        palantirJavaFormat(libs.findVersion("palantir-java-format").get().requiredVersion).style("PALANTIR")
        removeUnusedImports()
        importOrder("\\#", "")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // Unit tests follow the *Test convention; packaged acceptance tests (*IT) run in their own task.
    include("**/*Test.class")
    // Path-validation tests compare real paths, so give test JVMs a temporary directory without symbolic links
    // (macOS resolves /var through one) or short names (Windows).
    systemProperty("java.io.tmpdir", Path.of(System.getProperty("java.io.tmpdir")).toRealPath().toString())
}
