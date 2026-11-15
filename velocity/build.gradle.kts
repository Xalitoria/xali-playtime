import mc.xalitoria.gradle.sibling

plugins {
    kotlin("plugin.serialization")
    alias(libs.plugins.shadow)
}

// Declared with api, so the proxy downloads them when the plugin loads instead of the jar carrying them (:playtime:common
// leaves Exposed to whoever packages it).
dependencies {
    implementation(sibling("common"))
    api(libs.kaml)
    api(libs.kotlinx.serialization)
    api(libs.kotlinx.coroutines)
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.bundles.database)
    compileOnly(libs.velocity.api)
    compileOnly(libs.plan) { isTransitive = false }
    testImplementation(libs.kaml)
    testImplementation(libs.plan)
    testImplementation(libs.kotlinx.serialization)
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
