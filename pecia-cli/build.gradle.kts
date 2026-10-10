plugins {
    id("pecia.java-conventions")
    alias(libs.plugins.shadow)
}

dependencies {
    implementation(project(":pecia-core"))
    implementation(libs.picocli)
    annotationProcessor(libs.picocli.codegen)
    // Silences JGit's SLF4J "no logger found" warning on stderr; we do not log.
    runtimeOnly(libs.slf4j.nop)

    testImplementation(testFixtures(project(":pecia-core")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.compileJava {
    options.compilerArgs.addAll(
        listOf(
            // picocli-codegen inspects annotations without claiming them, which javac reports.
            "-Xlint:-processing",
            "-Aproject=${project.group}/${project.name}",
        )
    )
}

tasks.shadowJar {
    archiveFileName = "pecia.jar"
    manifest {
        attributes("Main-Class" to "dev.eyuppastirmaci.pecia.Bootstrap")
    }
    // Drop dependency signature files; they no longer match once everything is merged into one JAR.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/*.EC")
}

tasks.named<Test>("integrationTest") {
    val cliJar = tasks.shadowJar.flatMap { it.archiveFile }
    inputs.file(cliJar).withPropertyName("cliJar")
    jvmArgumentProviders.add(CommandLineArgumentProvider { listOf("-Dpecia.it.jar=${cliJar.get().asFile.absolutePath}") })
}
