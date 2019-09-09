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
    target {
        useCommonJs()
        browser {
            webpackTask {
                sourceMaps = false
            }
        }
    }
    sourceSets {
        main {
            dependencies {
                implementation(npm("file-type", "12.3.0"))
                implementation(npm("is-svg", "4.2.0"))
            }
        }
    }
}
