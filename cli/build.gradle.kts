plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization") version "1.9.0"
}

kotlin {
    jvm {
        jvmToolchain(17)
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
            maxHeapSize = "2g"
            systemProperty("smedit.repositoryRoot", rootProject.projectDir.absolutePath)
        }
    }

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(project(":shared"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.10.0")
            }
        }
    }
}

tasks.register<JavaExec>("normalizeProjects") {
    group = "maintenance"
    description = "Explicit offline project normalization; previews by default, --apply writes with recovery copies"
    dependsOn("jvmMainClasses")
    mainClass.set("com.supermetroid.editor.cli.tools.ProjectFormatToolKt")
    classpath = kotlin.jvm().compilations["main"].runtimeDependencyFiles +
        kotlin.jvm().compilations["main"].output.allOutputs
    workingDir(rootProject.projectDir)
    maxHeapSize = "2g"
    args = parseCliArgs(project.findProperty("args") as? String ?: "projects")
}

tasks.register<JavaExec>("runCli") {
    val cliArgs: String = project.findProperty("args") as? String ?: ""
    mainClass.set("com.supermetroid.editor.cli.CliMainKt")
    classpath = kotlin.jvm().compilations["main"].runtimeDependencyFiles +
        kotlin.jvm().compilations["main"].output.allOutputs
    // Parse args respecting quoted strings
    this.args = parseCliArgs(cliArgs)
}

fun parseCliArgs(input: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var inQuote = false
    var quoteChar = ' '
    for (c in input) {
        when {
            !inQuote && (c == '"' || c == '\'') -> { inQuote = true; quoteChar = c }
            inQuote && c == quoteChar -> inQuote = false
            !inQuote && c == ' ' -> {
                if (current.isNotEmpty()) { result.add(current.toString()); current.clear() }
            }
            else -> current.append(c)
        }
    }
    if (current.isNotEmpty()) result.add(current.toString())
    return result
}
