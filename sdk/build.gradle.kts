plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

// Client SDK version (git tag sdk-vX.Y.Z on release). JitPack serves the only published artifact of the repository
// as com.github.sonanjip:nunsori:<tag>; clients substitute that module with this project in a composite build.
group = "com.github.taehunkim.nunsori"
version = "0.1.0"

android {
    namespace = "io.github.taehunkim.nunsori.sdk"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$version\"")
    }
    buildFeatures { aidl = true; buildConfig = true }
    publishing { singleVariant("release") { withSourcesJar() } }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "sdk"
            afterEvaluate { from(components["release"]) }
        }
    }
}
