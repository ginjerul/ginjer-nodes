version = "0.0.15"

val outputJarName = "nodes"

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.3.0-RC"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
    id("org.jlleitschuh.gradle.ktlint") version "14.0.1"
    id("com.gradleup.shadow") version "8.3.6"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.dmulloy2.net/nexus/repository/public/")
}

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib")
    compileOnly(files("libs/xc.jar"))
    compileOnly("com.google.code.gson:gson:2.13.2")
    implementation("org.bstats:bstats-bukkit:3.1.0")

    paperweight.paperDevBundle("1.21.10-R0.1-SNAPSHOT")
}

tasks {
    assemble {
        dependsOn(reobfJar)
    }

    // bStats is bundled (and moved to our own package, as bStats requires); nothing else is shaded
    shadowJar {
        archiveBaseName.set(outputJarName)
        dependencies {
            include(dependency("org.bstats:.*"))
        }
        relocate("org.bstats", "phonon.nodes.libs.bstats")
    }

    reobfJar {
        inputJar.set(shadowJar.flatMap { it.archiveFile })
    }

    jar {
        archiveBaseName.set(outputJarName)
    }
}
