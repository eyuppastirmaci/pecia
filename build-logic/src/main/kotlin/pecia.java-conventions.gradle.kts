import dev.eyuppastirmaci.pecia.build.CheckDuplicateClasses
import java.nio.file.Path
import java.util.Locale

plugins {
    java
    id("com.diffplug.spotless")
}

group = "dev.eyuppastirmaci"
version = "0.1.0-SNAPSHOT"

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

// Test classes that take at least this long are reported while their task runs.
val SLOW_TEST_CLASS_SECONDS = 5.0

java {
    toolchain {
        // Packaged offline tests install a SecurityManager, which JDK 24 and later no longer support.
        languageVersion = JavaLanguageVersion.of(21)
    }
}

// Every dependency must resolve to a single requested version instead of Gradle silently picking the highest.
// Plugin and annotation-processor classpaths are not checked.
configurations.matching { it.name.endsWith("Classpath") }.configureEach {
    resolutionStrategy.failOnVersionConflict()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    // Pecia never uses Java serialization; exceptions stay inside the running process.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}

// No class may come from two classpath entries with different content; checked before the CLI JAR merges them.
val checkDuplicateClasses = tasks.register<CheckDuplicateClasses>("checkDuplicateClasses") {
    description = "Fails when a class comes from two classpath entries with different content."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    classpath.from(sourceSets.main.map { it.output }, configurations.runtimeClasspath)
    report = layout.buildDirectory.file("reports/duplicate-classes.txt")
}

tasks.check {
    dependsOn(checkDuplicateClasses)
}

tasks.matching { it.name == "shadowJar" }.configureEach {
    dependsOn(checkDuplicateClasses)
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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    failOnNoDiscoveredTests = true
    // Test classes run in parallel JVMs, about one per two processors and at most four, which keeps CI runners
    // (three or four processors) busy without overloading them; -Ppecia.testForks=N overrides it for measurements.
    maxParallelForks = providers
        .gradleProperty("pecia.testForks")
        .map(String::toInt)
        .getOrElse(((Runtime.getRuntime().availableProcessors() + 1) / 2).coerceIn(1, 4))
    // A test that hangs fails after five minutes instead of stalling until the CI job times out, and prints every
    // thread's stack first. Each test method runs in its own thread so that the timeout also ends a test blocked in a
    // call that ignores interrupts, such as a read from a child process; @Timeout sets a tighter limit where needed.
    systemProperty("junit.jupiter.execution.timeout.default", "5 m")
    systemProperty("junit.jupiter.execution.timeout.thread.mode.default", "SEPARATE_THREAD")
    systemProperty("junit.jupiter.execution.timeout.threaddump.enabled", "true")
    // Path-validation tests compare real paths, so give test JVMs a temporary directory without symbolic links
    // (macOS resolves /var through one) or short names (Windows).
    systemProperty("java.io.tmpdir", Path.of(System.getProperty("java.io.tmpdir")).toRealPath().toString())
    // Gradle prints nothing while a test task runs, so a slow run looks like a hang: report each slow test class as it
    // finishes, and the counts once per task.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}

        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            val className = suite.className
            val seconds = (result.endTime - result.startTime) / 1000.0

            if (suite.parent == null) {
                logger.lifecycle(
                    "$path: ${result.testCount} tests, ${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped"
                )
            } else if (className != null && suite.name == className && seconds >= SLOW_TEST_CLASS_SECONDS) {
                logger.lifecycle(
                    "$path: ${className.substringAfterLast('.')} took ${String.format(Locale.ROOT, "%.1f", seconds)} s"
                )
            }
        }

        override fun beforeTest(testDescriptor: TestDescriptor) {}

        override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {}
    })
}

tasks.named<Test>("test") {
    // Unit tests follow the *Test convention; packaged acceptance tests (*IT) run in integrationTest.
    include("**/*Test.class")
    // Unit tests never use the user's cache: a semantic query places the vector extension in whatever cache it finds.
    environment("PECIA_CACHE_DIR", layout.buildDirectory.dir("test-cache").get().asFile.absolutePath)
}

// Gradle skips a test task whose filter matches no class files, so check explicitly that acceptance tests exist.
val requireIntegrationTests = tasks.register("requireIntegrationTests") {
    description = "Fails when the test source set contains no packaged acceptance tests (*IT)."
    val acceptanceTests = sourceSets.test.get().output.classesDirs.asFileTree.matching { include("**/*IT.class") }
    inputs.files(acceptanceTests).withPropertyName("acceptanceTests")
    dependsOn(tasks.testClasses)
    doLast {
        if (acceptanceTests.isEmpty) {
            throw GradleException("No packaged acceptance tests (*IT) found in the test source set")
        }
    }
}

// Packaged acceptance tests share the test source set and run against the built JARs, which each module passes in.
val integrationTest = tasks.register<Test>("integrationTest") {
    description = "Runs the packaged acceptance tests (*IT)."
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/*IT.class")
    // Like the unit tests, never the user's cache or one the environment names: CI sets PECIA_CACHE_DIR to the real
    // model's cache for referenceTest, and a packaged run that found the model would search differently.
    environment("PECIA_CACHE_DIR", layout.buildDirectory.dir("integration-test-cache").get().asFile.absolutePath)
    shouldRunAfter(tasks.test)
    dependsOn(requireIntegrationTests)
}

tasks.check {
    dependsOn(integrationTest)
}
