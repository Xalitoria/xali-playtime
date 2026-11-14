import mc.xalitoria.gradle.Load
import mc.xalitoria.gradle.RelativeLoad

plugins {
    kotlin("jvm")
    id("mc.xalitoria.conventions")
    id("mc.xalitoria.minecraft")
    alias(libs.plugins.shadow) apply false
}

subprojects {
    // Named for the package of what the module holds, see the Packages section of the root README.
    group = when (name) {
        "api" -> "net.xalitoria.playtime"
        "common" -> "com.xalitoria.playtime"
        "paper" -> "mc.xalitoria.playtime.paper"
        "velocity" -> "mc.xalitoria.playtime.velocity"
        else -> error("No group for $path")
    }
    version = "0.1.0"
}

tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}

paper {
    name = "XaliPlaytime"
    main = "mc.xalitoria.playtime.paper.XaliPlaytime"
    prefix = "XaliPlaytime"
    author = "audizian"
    website = "https://xalitoria.com"
    description = "Counts how long players are actually playing, not AFK, with leaderboards, an API for other plugins and Plan pages."
    apiVersion = "26.2"
    load = Load.POSTWORLD
    foliaSupported = true
    relocateSources = true
    serverDependencies {
        register("PlaceholderAPI") { load = RelativeLoad.BEFORE; required = false }
        // Plan is hooked whenever it enables, so it needs no order, but a server without it must still start.
        register("Plan") { required = false }
    }
}

velocity {
    name = "XaliPlaytime"
    id = "xaliplaytime"
    main = "mc.xalitoria.playtime.velocity.XaliPlaytime"
    author = "audizian"
    url = "https://xalitoria.com"
    description = "Adds up the play time of every server for Plan's network pages."
    relocateSources = true
    dependencies {
        // Loaded first, so its classes can be seen; the extension is registered when Plan enables.
        register("plan") { required = false }
    }
}
