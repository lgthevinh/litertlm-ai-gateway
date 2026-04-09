plugins {
    kotlin("jvm") version "2.3.10"
    id("io.ktor.plugin") version "3.4.2"
}

group = "org.thingai.app.aigateway"
version = "1.0"

repositories {
    mavenCentral()
    google()
}

application {
    mainClass.set("org.thingai.app.aigateway.MainKt")
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.10.0")

    implementation("io.ktor:ktor-server-core-jvm:3.4.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.4.2")
    implementation("org.slf4j:slf4j-simple:2.0.17") // For KTor

    implementation(files("libs/applicationbase.jar"))
    implementation(files("libs/desktopplatform.jar"))

    // appbase and desktopplatform dependencies
    implementation("org.xerial:sqlite-jdbc:3.43.2.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("com.google.code.gson:gson:2.13.2")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}