import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Private, never shared: your WMATA key and your own starting stations (see private.properties).
// Without it (a clean copy from GitHub), builds have no key and start at Metro Center ⇄ Union Station.
val ownerProps: Properties? = rootProject.file("private.properties").takeIf { it.exists() }
    ?.let { f -> Properties().also { props -> f.inputStream().use { props.load(it) } } }
val ownerKey: String = ownerProps?.getProperty("wmataKey").orEmpty().trim()
val ownerDefaults: String? = ownerProps?.getProperty("startDefaults")?.trim()?.takeIf { it.count { c -> c == '|' } == 7 }
val neutralDefaults = "RD|A01|Metro Center|B03|Union Station|Washington, DC|38.8983|-77.0281"

// The release key's settings (private: see signingConfigs below).
val releaseSigning: Properties? = rootProject.file("signing.properties").takeIf { it.exists() }
    ?.let { f -> Properties().also { props -> f.inputStream().use { props.load(it) } } }

android {
    namespace = "com.metrocast.app"
    compileSdk = 35

    defaultConfig {
        // Your WMATA key (from private.properties) is built in. For a copy to share publicly, build with -PpublicBuild:
        // no key inside, and the app asks each person for their own free key.
        val publicBuild = project.hasProperty("publicBuild")
        buildConfigField("String", "WMATA_KEY", "\"" + (if (publicBuild) "" else ownerKey) + "\"")
        // Starting trips and weather spot (line|from code|from name|to code|to name|place|lat|lon).
        // Your build keeps your own; the public one uses neutral downtown-DC defaults.
        // Optional sections: -Prail=false leaves out Amtrak/MARC/VRE, -Pairports=false leaves out Airports.
        buildConfigField("boolean", "RAIL", ((project.findProperty("rail") as String?) ?: "true").toBoolean().toString())
        buildConfigField("boolean", "AIRPORTS", ((project.findProperty("airports") as String?) ?: "true").toBoolean().toString())
        // Live flight tracking services built in: -Pflights=aviationstack, -Pflights=flightaware, or both (default).
        val flights = (project.findProperty("flights") as String?) ?: "aviationstack,flightaware"
        buildConfigField("String", "FLIGHT_PROVIDERS", "\"$flights\"")
        buildConfigField("String", "START_DEFAULTS", "\"" + (if (publicBuild) neutralDefaults else ownerDefaults ?: neutralDefaults) + "\"")
        applicationId = "com.metrocast.app"
        minSdk = 26
        targetSdk = 35
        // The version lives in version.txt (e.g. 1.17). Its number for Android: 1.17 → 1017, 1.18 → 1018…,
        // so each new version counts as newer (needed for updates from your PC).
        val ver = rootProject.file("version.txt").readText().trim()
        val (major, minor) = ver.split('.').let { it[0].toInt() to (it.getOrNull(1)?.toInt() ?: 0) }
        versionCode = major * 1000 + minor
        versionName = ver
        // Phones only (ARM): the x86 copies of native bits are for computer emulators.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // The app is in English: leave out the ~80 languages of translations that Android's libraries carry.
        androidResources.localeFilters += listOf("en")
        // The version, readable inside the APK (the PC's MetroCast Updates window reads it when you drop one in).
        val versionAssets = project.layout.buildDirectory.dir("generated/versionAsset").get().asFile
        versionAssets.mkdirs()
        versionAssets.resolve("metrocast-version.txt").writeText(ver)
        // What changed in this version (whatsnew.txt, one line each), shown once after an update.
        versionAssets.resolve("metrocast-whatsnew.txt").writeText(rootProject.file("whatsnew.txt").takeIf { it.exists() }?.readText()?.trim().orEmpty())
        sourceSets.getByName("main").assets.srcDir(versionAssets)
        // -Pupdates=true: the copy that can update itself from a folder on your PC over home Wi-Fi.
        val updates = project.hasProperty("updates") && project.property("updates") == "true"
        buildConfigField("boolean", "UPDATES", updates.toString())
        manifestPlaceholders["netConfig"] = if (updates) "@xml/network_security_config_home" else "@xml/network_security_config"
        manifestPlaceholders["installPermission"] = if (updates) "android.permission.REQUEST_INSTALL_PACKAGES" else "com.metrocast.app.permission.NONE"
    }

    // The key every MetroCast APK so far was signed with, kept in the project so builds from any
    // computer install over the existing app. (A sideloading key, not for the Play Store.)
    signingConfigs {
        // Test/debug builds only.
        create("metrocast") {
            storeFile = rootProject.file("metrocast-signing.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // The real release key (since 1.20). Its password lives in signing.properties, kept private with
        // metrocast-release.jks: never share either. Losing them means installed copies can't be updated.
        val p = releaseSigning
        if (p != null) {
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // The owner's test key if it's there; otherwise Android's standard debug key.
            if (rootProject.file("metrocast-signing.keystore").exists()) signingConfig = signingConfigs.getByName("metrocast")
            if (ownerKey.isEmpty() && !project.hasProperty("publicBuild")) buildConfigField("String", "WMATA_KEY", "\"test-key-not-real\"")
        }
        release {
            // Shrink unused code/resources (smaller, faster APK); see proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the MetroCast release key when signing.properties is there (the owner's copy);
            // otherwise the release APK is unsigned. Never quietly signed with the test key.
            // (build_package.sh refuses any APK that isn't signed with the release key.)
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // Screenshots of the map screens are saved only when asked for: -Pshots=<dir>
        unitTests.all { t -> (project.findProperty("shots") as String?)?.let { t.systemProperty("metrocast.shots", it) } }
        // Simulated phones for N seconds each, in parallel: -Psim=<seconds> -PsimOut=<file>
        unitTests.all { t ->
            (project.findProperty("sim") as String?)?.let { t.systemProperty("metrocast.sim", it); t.maxParallelForks = 3; t.forkEvery = 1 }
            (project.findProperty("simOut") as String?)?.let { t.systemProperty("metrocast.simout", it) }
        }
        // Diagnosis against the real WMATA: -PliveCheck
        unitTests.all { t -> if (project.hasProperty("liveCheck")) t.systemProperty("metrocast.livecheck", "1") }
        // Screenshots to share: -PshareShots=<dir>
        unitTests.all { t -> (project.findProperty("shareShots") as String?)?.let { t.systemProperty("metrocast.shareshots", it) } }
        // Rebuilds the built-in Amtrak/MARC/VRE schedules from downloaded zips: -Prailgen=<dir>
        unitTests.all { t -> (project.findProperty("railgen") as String?)?.let { t.systemProperty("metrocast.railgen", it) } }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    // Installs the startup profile (baseline-prof.txt) so the app is pre-compiled: faster start.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Home-screen widget
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.datastore:datastore-preferences:1.1.4")
    implementation("androidx.work:work-runtime-ktx:2.10.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Unit tests (mock live trips on recorded WMATA data). org.json replaces Android's stub on the JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    // Android code on the JVM (screens, settings, notifications) — no emulator needed.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.test.ext:junit-ktx:1.2.1")
    testImplementation("androidx.work:work-testing:2.10.1")
    testImplementation("androidx.glance:glance-testing:1.1.1")
    testImplementation("androidx.glance:glance-appwidget-testing:1.1.1")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
