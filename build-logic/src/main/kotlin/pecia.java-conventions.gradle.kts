import dev.eyuppastirmaci.pecia.build.CheckDuplicateClasses
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
    // Path-validation tests compare real paths, so give test JVMs a temporary directory without symbolic links
    // (macOS resolves /var through one) or short names (Windows).
    systemProperty("java.io.tmpdir", Path.of(System.getProperty("java.io.tmpdir")).toRealPath().toString())
    // One line per test task, so build logs show the counts.
    addTestListener(object : TestListener {
        override fun beforeSuite(suite: TestDescriptor) {}

        override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            if (suite.parent == null) {
                logger.lifecycle(
                    "$path: ${result.testCount} tests, ${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped"
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
    shouldRunAfter(tasks.test)
    dependsOn(requireIntegrationTests)
}

tasks.check {
    dependsOn(integrationTest)
}
