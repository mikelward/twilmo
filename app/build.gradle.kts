plugins {
    alias(libs.plugins.android.application)
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20"
}

// GitHub Actions sets CI=true. Distinguishes the builds that ship from a build
// made on a developer's machine; drives R8, the launcher identity, and the
// debug application ID, mirroring the sibling repos.
val isCiBuild: Boolean = System.getenv("CI") == "true"

// A locally built debug APK is `.dev`, not `.debug`, so it co-installs beside
// the CI tester build instead of fighting it for one package name: they are
// signed by different keys, so sharing an ID is an install failure, not an
// upgrade. CI keeps `.debug` for the tester build.
val debugApplicationIdSuffix = if (isCiBuild) ".debug" else ".dev"

// Reads git metadata for versionCode/versionName. In CI a failure is fatal:
// a silent fallback would ship a mislabeled artifact (versionCode 1) that
// Play rejects — or worse, accepts — instead of failing the build with a
// diagnosis. Outside CI (a source-tarball build, git missing) the fallback is
// fine and is logged rather than swallowed.
fun gitOutput(vararg args: String, fallback: String): String {
    val command = "git " + args.joinToString(" ")
    val output = try {
        providers.exec {
            commandLine("git", *args)
            isIgnoreExitValue = true
        }.standardOutput.asText.get().trim()
    } catch (e: Exception) {
        if (isCiBuild) {
            throw GradleException(
                "$command failed; CI derives versionCode/versionName from git metadata",
                e,
            )
        }
        logger.lifecycle("$command unavailable (${e.message}); using fallback \"$fallback\"")
        return fallback
    }
    if (output.isEmpty()) {
        if (isCiBuild) {
            error("$command produced no output; CI derives versionCode/versionName from git metadata")
        }
        logger.lifecycle("$command produced no output; using fallback \"$fallback\"")
        return fallback
    }
    return output
}

// Monotonic versionCode as long as main only moves forward; Play rejects an
// AAB whose versionCode is <= the highest already uploaded. CI checks out with
// fetch-depth: 0 so the count isn't truncated by a shallow clone.
val gitCommitCount: Int =
    gitOutput("rev-list", "--count", "HEAD", fallback = "1").toIntOrNull() ?: 1
val gitShortSha: String = gitOutput("rev-parse", "--short", "HEAD", fallback = "unknown")
val baseVersionName = "0.1"

// Which launcher identity each build wears, resolved at manifest-merge time so
// a phone carrying more than one Twilmo says which is which from the home
// screen. Only the Play build gets the plain icon; the CI tester gets the dark
// "debug" variant and anything built outside CI gets the amber "dev" variant.
val devLauncherIcon = "@mipmap/ic_launcher_dev"
val releaseLauncherIcon = if (isCiBuild) "@mipmap/ic_launcher" else devLauncherIcon
val debugLauncherIcon = if (isCiBuild) "@mipmap/ic_launcher_debug" else devLauncherIcon

// Same distinction in the name under the icon. The shipping label stays a
// string resource so it can be localized; the badged labels are literals that
// never reach a store listing. The in-app title keeps reading @string/app_name
// so recorded screenshots don't differ between a local run and CI.
val devAppLabel = "Twilmo Dev"
val releaseAppLabel = if (isCiBuild) "@string/app_name" else devAppLabel
val debugAppLabel = if (isCiBuild) "Twilmo Debug" else devAppLabel

android {
    namespace = "app.twilmo"
    // Latest platform the remote-session provisioning hook seeds (matches the
    // sibling repos).
    compileSdk = 36

    defaultConfig {
        applicationId = "app.twilmo"
        // minSdk 34 (Android 14) as a courtesy floor; the product is designed
        // and tested against Android 16+ (SPEC "Devices and compatibility").
        minSdk = 34
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "$baseVersionName.$gitCommitCount+$gitShortSha"
    }

    signingConfigs {
        // CI materializes a stable debug keystore from a secret so successive
        // tester builds carry the same signature and install as updates. Local
        // builds without the env var fall through to AGP's auto-generated
        // ~/.android/debug.keystore.
        getByName("debug") {
            val keystorePath = providers.environmentVariable("DEBUG_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("DEBUG_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("DEBUG_KEY_ALIAS").getOrElse("androiddebugkey")
                keyPassword = providers.environmentVariable("DEBUG_KEY_PASSWORD").orNull
            }
        }
        // CI materializes the Play upload keystore from a secret. Local builds
        // without RELEASE_KEYSTORE_FILE produce an unsigned release AAB, so
        // forks and fresh clones build cleanly.
        create("release") {
            val keystorePath = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
            if (!keystorePath.isNullOrEmpty() && file(keystorePath).exists()) {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // R8 in CI only: shipping builds are CI-built so they ship
            // minified, while a local release build skips R8 and stays fast to
            // inspect.
            isMinifyEnabled = isCiBuild
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (!providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull.isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            manifestPlaceholders["launcherIcon"] = releaseLauncherIcon
            manifestPlaceholders["appLabel"] = releaseAppLabel
        }
        debug {
            applicationIdSuffix = debugApplicationIdSuffix
            // Same shrink-only R8 as release, CI-only — so testers and the PR
            // build job exercise the pipeline the shipping build uses, while
            // local debug builds skip R8 and stay fast.
            isMinifyEnabled = isCiBuild
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            manifestPlaceholders["launcherIcon"] = debugLauncherIcon
            manifestPlaceholders["appLabel"] = debugAppLabel
        }
    }

    buildFeatures {
        compose = true
        // VERSION_NAME as a compile-time constant so showing the version never
        // needs a PackageManager IPC.
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<Test>().configureEach {
    if (project.hasProperty("roborazzi.test.record")) {
        jvmArgs("-Droborazzi.test.record=true")
    }
    if (project.hasProperty("roborazzi.test.verify")) {
        jvmArgs("-Droborazzi.test.verify=true")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit)
    // Test-only: sealedSubclasses for the state machine's sample-coverage
    // check; never on the app classpath.
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
}
