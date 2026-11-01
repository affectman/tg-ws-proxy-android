import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.chaquopy)
}

// The repository root is the upstream tg-ws-proxy checkout. Its proxy/ package is
// copied into the APK as is; nothing under it is edited for Android.
val upstreamRoot: File = rootDir.parentFile
val upstreamVersion: String = Regex("""__version__\s*=\s*"([^"]+)"""")
    .find(upstreamRoot.resolve("proxy/__init__.py").readText())
    ?.groupValues?.get(1)
    ?: error("Cannot read __version__ from proxy/__init__.py")

// Commit count of HEAD: grows with every merge from upstream and every own commit,
// so versionCode needs no manual bumps. CI checks out full history for this.
val gitCommitCount: Int = providers.environmentVariable("TGWS_VERSION_CODE").orNull?.toInt()
    ?: runCatching {
        providers.exec {
            commandLine("git", "rev-list", "--count", "HEAD")
            workingDir = upstreamRoot
        }.standardOutput.asText.get().trim().toInt()
    }.getOrDefault(1)

// Release signing: -Ptgws.signing=<keystore.properties> locally, or TGWS_KEYSTORE_FILE
// with _PASSWORD / TGWS_KEY_ALIAS / TGWS_KEY_PASSWORD in CI. Without them release
// builds use the debug key, which is fine locally and refused by CI on release.
val releaseSigning: Map<String, String>? =
    providers.gradleProperty("tgws.signing").orNull?.let { path ->
        val props = Properties()
        file(path).inputStream().use { props.load(it) }
        props.stringPropertyNames().associateWith { props.getProperty(it).trim() }
    } ?: providers.environmentVariable("TGWS_KEYSTORE_FILE").orNull?.let { storeFile ->
        fun env(name: String) = providers.environmentVariable(name).orNull
            ?: error("$name must be set together with TGWS_KEYSTORE_FILE")
        mapOf(
            "storeFile" to storeFile,
            "storePassword" to env("TGWS_KEYSTORE_PASSWORD"),
            "keyAlias" to env("TGWS_KEY_ALIAS"),
            "keyPassword" to env("TGWS_KEY_PASSWORD"),
        )
    }

val upstreamPythonDir = layout.buildDirectory.dir("generated/upstream-python")

val syncUpstreamProxy = tasks.register<Sync>("syncUpstreamProxy") {
    from(upstreamRoot.resolve("proxy")) {
        include("**/*.py")
    }
    into(upstreamPythonDir.map { it.dir("proxy") })
}

android {
    namespace = "io.github.affectman.tgwsproxy"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.affectman.tgwsproxy"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "$upstreamVersion-android.$gitCommitCount"

        buildConfigField("String", "CORE_VERSION", "\"$upstreamVersion\"")
        buildConfigField("String", "GITHUB_REPO", "\"affectman/tg-ws-proxy-android\"")

        ndk {
            // Chaquopy's Python 3.13 wheels for cryptography are 64-bit ARM only.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getValue("storeFile"))
                storePassword = releaseSigning.getValue("storePassword")
                keyAlias = releaseSigning.getValue("keyAlias")
                keyPassword = releaseSigning.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        generateLocaleConfig = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf(
            // Versions are pinned on purpose and bumped together with Chaquopy checks.
            "GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion",
            // targetSdk moves only after testing the new platform's background rules on a phone.
            "OldTargetApi",
            // Chaquopy's cryptography wheel for Python 3.13 exists for arm64-v8a only.
            "ChromeOsAbiSupport",
        )
        warningsAsErrors = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

chaquopy {
    defaultConfig {
        version = "3.13"
        // Optional: -Ptgws.buildPython=C:/path/python.exe when Python 3.13 isn't on PATH.
        providers.gradleProperty("tgws.buildPython").orNull?.let { buildPython(it) }
        pip {
            install("-r", "requirements.txt")
        }
        pyc {
            src = true
            pip = true
        }
    }
    sourceSets {
        getByName("main") {
            srcDir(upstreamPythonDir.get().asFile)
        }
    }
}

// Chaquopy's per-variant Python tasks read the generated directory.
tasks.configureEach {
    if (name != syncUpstreamProxy.name && name.contains("Python")) {
        dependsOn(syncUpstreamProxy)
    }
}
tasks.named("preBuild") { dependsOn(syncUpstreamProxy) }

// Chaquopy writes build/python/proguard-rules.pro while the build is configured, so a
// `clean` in the same Gradle run would delete it before R8 reads it.
tasks.named<Delete>("clean") {
    setDelete(fileTree(layout.buildDirectory) { exclude("python/proguard-rules.pro") })
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.graphics.shapes)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
