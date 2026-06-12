plugins {
    kotlin("jvm") version "2.4.0"
    id("com.gradleup.shadow") version "9.4.2"
}

group = "com.hetzner.hackathon"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.69-stable")
    implementation(kotlin("stdlib"))
    implementation("com.google.code.gson:gson:2.11.0")
}

java {
    // We disable toolchain auto-provisioning by relying on the environment's JAVA_HOME
    // which we will set to the manually installed Java 25.
}

tasks {
    withType<JavaCompile>().configureEach {
        targetCompatibility = "25"
        sourceCompatibility = "25"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
        }
    }
    
    shadowJar {
        archiveClassifier.set("")
        relocate("kotlin", "com.hetzner.hackathon.libs.kotlin")
        relocate("com.google.gson", "com.hetzner.hackathon.libs.gson")
    }
    
    build {
        dependsOn(shadowJar)
    }
}
