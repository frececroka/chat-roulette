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
