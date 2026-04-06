plugins {
    kotlin("jvm") version "2.3.10"
}

group = "org.thingai.app.aigateway"
version = "1.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("com.google.ai.edge.litertlm:litertlm-jvm:0.10.0")

    implementation("io.ktor:ktor-server-core-jvm:3.4.2")
    implementation("io.ktor:ktor-server-netty-jvm:3.4.2")
    implementation("org.slf4j:slf4j-simple:2.0.17") // For KTor
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}