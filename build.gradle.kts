plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
    `kotlin-dsl`
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "io.github.spartanlabsgaming"
version = "1.0.0" as String

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.spartanlabsgaming:GameTools:3.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("MainKt")
}


mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates("io.github.spartanlabsgaming", "MyGameServer", "1.0.0")

    pom {
        name.set("My Game Server")
        description.set("A prototype for a game server.")
        inceptionYear.set("2026")
        url.set("https://github.com/SpartanLabsGaming/MyGameServer")
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
            url.set("https://github.com/SpartanLabsGaming/MyGameServer/")
            connection.set("scm:git:git://github.com/SpartanLabsGaming/MyGameServer.git")
            developerConnection.set("scm:git:ssh://git@github.com/SpartanLabsGaming/MyGameServer.git")
        }
    }
}

tasks.test {
    useJUnitPlatform()
}