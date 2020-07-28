import org.gradle.api.tasks.testing.logging.TestLogEvent.*

group =  "de.lorenzgorse"
version = "1.0-SNAPSHOT"

val ktorVersion by extra ("1.3.1")

repositories {
    mavenLocal()
    mavenCentral()
}

plugins {
    kotlin("jvm") version "1.3.50"
    application
}

application {
    mainClassName = "de.lorenzgorse.chatroulette.AppKt"
    applicationDefaultJvmArgs = listOf("-Xmx500M")
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("ch.qos.logback:logback-classic:1.2.3")
    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-gson:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-websockets:$ktorVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:5.5.2")
}


/* Copy compiled fronted resources to the static files directory. */

task<Copy>("copyFrontendArtifacts") {
    dependsOn("browser:browserWebpack")
    dependsOn("browser:processResources")
    from("browser/build/distributions/browser-1.0-SNAPSHOT.js") {
        rename { "main.js" }
    }
    from("browser/build/processedResources/Js/main")
    into("build/resources/main/static")
}

tasks {
    processResources {
        dependsOn("copyFrontendArtifacts")
    }
}


/* Setup testing. */

tasks.test {
    useJUnitPlatform()
    testLogging {
        this.events(FAILED, PASSED, SKIPPED, STARTED)
    }
}


/* Run the loadtest. */

task<JavaExec>("loadtest") {
    classpath = sourceSets.test.get().runtimeClasspath
    main = "de.lorenzgorse.chatroulette.LoadtestKt"
    maxHeapSize = "2G"
}
