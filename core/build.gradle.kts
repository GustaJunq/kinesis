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
}
