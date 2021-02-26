import org.gradle.api.tasks.testing.logging.TestLogEvent.*
import org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpack
import org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest

group =  "de.lorenzgorse"
version = "1.0-SNAPSHOT"

val ktorVersion by extra ("1.5.1")

repositories {
    mavenLocal()
    mavenCentral()
}

plugins {
    kotlin("multiplatform") version "1.4.30"
    application
}

kotlin {
    jvm {
        withJava()
    }
    js {
        browser()
    }

    sourceSets {
        val jsMain by getting {
            dependencies {
                implementation(npm("file-type", "16.2.0"))
                implementation(npm("is-svg", "4.2.1"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.2")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("ch.qos.logback:logback-classic:1.2.3")
                implementation("io.ktor:ktor-server-netty:$ktorVersion")
                implementation("io.ktor:ktor-gson:$ktorVersion")
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
                implementation("io.ktor:ktor-websockets:$ktorVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.1.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.4.2")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("com.xenomachina:kotlin-argparser:2.0.7")
                implementation("org.junit.jupiter:junit-jupiter:5.5.2")
            }
        }
    }
}

application {
    mainClassName = "de.lorenzgorse.chatroulette.AppKt"
    applicationDefaultJvmArgs = listOf("-Xmx500M")
}

task<Copy>("copyJsArtifacts") {
    val webpackTask = tasks.getByName<KotlinWebpack>("jsBrowserDevelopmentWebpack")
    dependsOn(webpackTask)
    from(webpackTask.outputFile)

    val jvmProcessResources = tasks.getByName<ProcessResources>("jvmProcessResources")
    into(jvmProcessResources.destinationDir.resolve("static"))
}

tasks.getByName<ProcessResources>("jvmProcessResources") {
    dependsOn(tasks.getByName("copyJsArtifacts"))
}

/* Setup testing. */

tasks.getByName<KotlinJvmTest>("jvmTest") {
    useJUnitPlatform()
    testLogging {
        this.events(FAILED, PASSED, SKIPPED, STARTED)
    }
}


/* Run the loadtest. */

//task<JavaExec>("loadtest") {
//    classpath = sourceSets.getByName("jvmTest").runtimeClasspath
//    main = "de.lorenzgorse.chatroulette.LoadtestKt"
//    maxHeapSize = "2G"
//}
