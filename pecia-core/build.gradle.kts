plugins {
    id("pecia.java-conventions")
    `java-library`
    `java-test-fixtures`
}

// Offline packaged-test support shared with the CLI's tests. These classes stay in src/test/java while the Maven
// build still compiles them there.
val offlineSupport =
    listOf("OfflineEnvironment", "OfflineSandbox", "NetworkGuard", "NetworkProbe").map {
        "dev/eyuppastirmaci/pecia/testing/$it.java"
    }

sourceSets {
    testFixtures {
        java {
            setSrcDirs(listOf("src/test/java"))
            setIncludes(offlineSupport)
        }
        resources.setSrcDirs(emptyList<File>())
    }
    test {
        java.exclude(offlineSupport)
    }
}

dependencies {
    implementation(libs.sqlite.jdbc)
    implementation(libs.commonmark)
    // Used only for its .gitignore rule engine (org.eclipse.jgit.ignore).
    implementation(libs.jgit)
    implementation(libs.tomlj)

    // Enforced like Maven's dependencyManagement, so jqwik's older JUnit Platform requests are not conflicts.
    testImplementation(enforcedPlatform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// The packaged-core consumer runs on the core JAR plus exactly these runtime dependencies.
val itRuntime = tasks.register<Sync>("itRuntime") {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("it-runtime"))
}

tasks.named<Test>("integrationTest") {
    val coreJar = tasks.jar.flatMap { it.archiveFile }
    val testClasses = sourceSets.test.flatMap { it.java.classesDirectory }
    inputs.file(coreJar).withPropertyName("coreJar")
    // Declaring the sync task as an input also makes this task depend on it.
    inputs.files(itRuntime).withPropertyName("runtimeDirectory")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "-Dpecia.it.jar=${coreJar.get().asFile.absolutePath}",
            "-Dpecia.it.runtimeDirectory=${itRuntime.get().destinationDir.absolutePath}",
            "-Dpecia.it.testClasses=${testClasses.get().asFile.absolutePath}",
        )
    })
}
