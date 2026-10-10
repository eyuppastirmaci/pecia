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

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testRuntimeOnly(libs.junit.platform.launcher)
}
