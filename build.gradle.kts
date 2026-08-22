plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
    `kotlin-dsl`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Only version 1.0.4 was directly confirmed on Maven Central at the
    // time this file was written. Verify 1.0.5 is actually published
    // before relying on it - if not, drop back to 1.0.4.
    implementation("io.github.spartanlaboratories:GameTools:1.0.5")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("MainKt")
}

kotlin {
    jvmToolchain(23)
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.spartanlaboratories", "MyGameServer", "1.0.0")

    pom {
        name.set("My Game Server")
        description.set("A prototype for a game server.")
        inceptionYear.set("2026")
        url.set("https://github.com/SpartanLaboratories/MyGameServer")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("SpaSinghOut")
                name.set("Spartak Singh")
                url.set("https://github.com/SpaSinghOut")
            }
        }
        scm {
            url.set("https://github.com/SpartanLaboratories/MyGameServer/")
            connection.set("scm:git:git://github.com/SpartanLaboratories/MyGameTools.git")
            developerConnection.set("scm:git:ssh://git@github.com/SpartanLaboratories/MyGameTools.git")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}