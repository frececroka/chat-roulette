group = "de.lorenzgorse"
version = "1.0-SNAPSHOT"

plugins {
    kotlin("js")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-js"))
    implementation("org.jetbrains.kotlinx", "kotlinx-coroutines-core", "1.4.2")
}

kotlin {
    js {
        browser {
        }
    }
    sourceSets {
        main {
            dependencies {
                implementation(npm("file-type", "16.2.0"))
                implementation(npm("is-svg", "4.2.1"))
            }
        }
    }
}
