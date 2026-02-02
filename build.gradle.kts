plugins {
    kotlin("jvm") version "2.3.0"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.primcraft"
version = "2026.2.2.1"

repositories {
    mavenCentral()
}

dependencies {
    implementation("net.minestom:minestom:2026.01.08-1.21.11")
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.shadowJar {
    archiveBaseName.set("lobby")
    archiveClassifier.set("")
    archiveVersion.set("")
    mergeServiceFiles()

    manifest {
        attributes["Main-Class"] = "com.primcraft.lobby.MainKt"
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
