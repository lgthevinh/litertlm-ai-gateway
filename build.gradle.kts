plugins {
    kotlin("jvm") version "2.3.10"
}

group = "org.thingai.app.aigateway"
version = "1.0"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))

    implementation("com.google.ai.edge.litertlm:litertlm-jvm:latest.release")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}