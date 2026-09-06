plugins {
    kotlin("jvm") version "2.4.10"
    application
}

group = "com.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.5.2"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktorVersion")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")

    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.example.ApplicationKt")
}

tasks.test {
    useJUnitPlatform()
}
