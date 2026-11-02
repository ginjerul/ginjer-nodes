version = "0.3.0"

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0-RC"
    id("com.gradleup.shadow") version "8.3.6"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // provided at runtime by the kotlin runtime plugin (same one Nodes uses)
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib")
    compileOnly("io.papermc.paper:paper-api:1.21.10-R0.1-SNAPSHOT")
    implementation("org.bstats:bstats-bukkit:3.1.0")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }

    // the jar to ship is the shadow jar (bStats bundled and moved to our own package)
    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName.set("ginjermap")
        archiveClassifier.set("")
        dependencies {
            include(dependency("org.bstats:.*"))
        }
        relocate("org.bstats", "phonon.ginjermap.libs.bstats")
    }

    assemble {
        dependsOn(shadowJar)
    }
}
