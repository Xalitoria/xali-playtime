import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// What the Paper and Velocity plugins share: the tables the play time is kept in and the queries that add the servers up. It
// uses no platform API, so both jars bundle it, and it targets Java 17 because Velocity may run on an older JVM than Paper.
// Exposed is compileOnly: Paper's library loader supplies it, and the Velocity plugin declares its own.
dependencies {
    compileOnly(libs.bundles.exposed)
    // Exposed asks for an older slf4j than the platforms bring; compiling only needs some.
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    testImplementation(libs.bundles.exposed)
    testImplementation(libs.bundles.database)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.test {
    useJUnitPlatform()
}
