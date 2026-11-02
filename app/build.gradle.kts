plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Single place to bump the app version: major.minor.patch, versionCode = major*10000 + minor*100 + patch.
val appVersionName = "0.7.1"
val appVersionCode = appVersionName.split(".").let { (a, b, c) -> a.toInt() * 10000 + b.toInt() * 100 + c.toInt() }

android {
    namespace = "com.lsa.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.taehunkim.nunsori"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    defaultConfig { ndk { abiFilters += listOf("arm64-v8a") } }
    androidResources { generateLocaleConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
    testOptions { unitTests.isReturnDefaultValues = true }
    // Room migration tests read the exported schemas.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

// Licenses shown in Settings › About: generated from the repo-root files so there is one source (git-ignored output).
val licensesAsset = tasks.register("generateLicensesAsset") {
    val notices = rootProject.file("THIRD_PARTY_NOTICES.md")
    val license = rootProject.file("LICENSE")
    val out = file("src/main/assets/licenses.txt")
    inputs.files(notices, license)
    outputs.file(out)
    doLast { out.writeText(notices.readText() + "\n\n----------------------------------------\n\n" + license.readText()) }
}
tasks.named("preBuild") { dependsOn(licensesAsset) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.jsoup)
    implementation(libs.pdfbox.android)
    implementation(libs.commons.compress)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.androidsvg)
    implementation(libs.litertlm.android)
    implementation(libs.mlkit.text.recognition.korean)
    // Not on Maven Central: scripts/fetch-sherpa-onnx.sh downloads it into app/libs (git-ignored).
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    // Room reads its schema bundle with kotlinx.serialization; an older runtime pulled in transitively breaks migration tests.
    implementation(libs.kotlinx.serialization.core)

    testImplementation(libs.junit)
    testImplementation("net.sf.kxml:kxml2:2.3.0") // XmlPullParser implementation for JVM unit tests
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
