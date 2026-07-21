plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.0"
}

val ktorVersion = "2.3.12"

kotlin {
    jvm {
        jvmToolchain(17)
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation("io.ktor:ktor-server-core-jvm:$ktorVersion")
                implementation("io.ktor:ktor-server-netty-jvm:$ktorVersion")
                implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktorVersion")
                implementation("io.ktor:ktor-server-cors-jvm:$ktorVersion")
                implementation("io.ktor:ktor-server-status-pages-jvm:$ktorVersion")
                implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
                runtimeOnly("org.slf4j:slf4j-simple:2.0.9")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.10.0")
                implementation("io.ktor:ktor-server-test-host-jvm:$ktorVersion")
            }
        }
    }
}

tasks.register<JavaExec>("runService") {
    description = "Run the SMEDIT patch service"
    group = "application"
    dependsOn("jvmMainClasses")
    mainClass.set("com.supermetroid.editor.service.SmeditServiceMainKt")
    val jvmTarget = kotlin.targets.getByName("jvm")
    val mainCompilation = jvmTarget.compilations.getByName("main")
    classpath = mainCompilation.output.allOutputs + mainCompilation.runtimeDependencyFiles!!
    workingDir = rootProject.projectDir
}
