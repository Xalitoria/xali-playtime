import mc.xalitoria.gradle.sibling
import mc.xalitoria.gradle.repo.kotlinx

plugins {
    kotlin("plugin.serialization")
    alias(libs.plugins.shadow)
}

// Declared with api, so the proxy downloads them when the plugin loads instead of the jar carrying them (:playtime:common
// leaves Exposed to whoever packages it).
dependencies {
    implementation(sibling("common"))
    api(libs.kaml)
    api(kotlinx("serialization-json"))
    api(kotlinx("coroutines-core"))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.bundles.database)
    compileOnly(libs.velocity.api)
    compileOnly(libs.plan) { isTransitive = false }
    testImplementation(libs.kaml)
    testImplementation(libs.plan)
    testImplementation(kotlinx("serialization-json"))
    testImplementation(libs.bundles.exposed)
    testImplementation(libs.bundles.database)
}

tasks {
    jar { enabled = false }
    test { useJUnitPlatform() }
    build {
        dependsOn(shadowJar)
    }
}
