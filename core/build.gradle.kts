import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    // Embedded, offline JavaScript interpreter for expressions (interpreted mode, no Java access, instruction budget).
    implementation(libs.rhino)

    testImplementation(libs.junit)
    testImplementation(libs.json.schema.validator)
}

tasks.test {
    // Tests validate the shipped examples against the official JSON Schema.
    systemProperty("kinesis.repoRoot", projectDir.parentFile.absolutePath)
    // The Rust library of native/ (cargo build --release), when it was built for this machine: the tests then also
    // run the native code and compare it with the Kotlin version.
    val nativeLib = file("../native/target/release")
    if (nativeLib.isDirectory) systemProperty("java.library.path", nativeLib.absolutePath)
}

// `alight2kns`: a self-contained jar (java -jar alight2kns.jar scan|convert ...), built by `./gradlew :core:alight2knsJar`.
tasks.register<Jar>("alight2knsJar") {
    group = "distribution"
    archiveBaseName.set("alight2kns")
    archiveVersion.set("")
    manifest { attributes["Main-Class"] = "dev.kinesis.core.alight.cli.Alight2Kns" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
