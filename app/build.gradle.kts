import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension

plugins {
  alias(libs.plugins.hilt)
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  alias(libs.plugins.firebase.crashlytics)
}

// Preserve the current Room schema as compiler-generated evidence. Historical
// schemas must be recovered from real prior versions, never reconstructed.
ksp {
  arg("room.schemaLocation", "$projectDir/schemas")
}

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.lanucalc.kxwzqm"
    minSdk = 23
    targetSdk = 36
    // Protected CI releases use the strictly increasing Actions run number.
    // The standalone development baseline is higher than signed v1.1.0 builds;
    // signed OTA updates still require the pinned stable certificate.
    versionCode = providers.environmentVariable("LANU_VERSION_CODE").orNull?.toIntOrNull() ?: 693
    versionName = "1.2.0"
    // Never surface a large-media option unless a separately approved,
    // production-verified server rollout explicitly enables this APK build.
    // Missing, malformed, or untrusted values always mean feature OFF.
    buildConfigField("boolean", "LANU_PRIVATE_MEDIA_UI_ENABLED",
      if (providers.environmentVariable("LANU_PRIVATE_MEDIA_UI_ENABLED").orNull == "true") "true" else "false")
    buildConfigField("String", "LANU_RELAY_URL", "\"${providers.environmentVariable("LANU_RELAY_URL").orNull ?: "https://talhknjcjaijqiqwdyhf.supabase.co/functions/v1/lanu-relay"}\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      // CI restores the stable key from protected Actions secrets on trusted pushes.
      // PR/local builds may use a transient debug key; NEVER call those APKs safe upgrades.
      val stableKeystore = System.getenv("LANU_SIGNING_KEYSTORE_PATH")
      storeFile = file(stableKeystore ?: "${rootDir}/debug.keystore")
      if (stableKeystore != null) storeType = "PKCS12"
      storePassword = if (stableKeystore != null) System.getenv("LANU_SIGNING_STORE_PASSWORD") else "android"
      keyAlias = if (stableKeystore != null) System.getenv("LANU_SIGNING_KEY_ALIAS") else "androiddebugkey"
      keyPassword = if (stableKeystore != null) System.getenv("LANU_SIGNING_KEY_PASSWORD") else "android"
    }
  }

  buildTypes {
    release {
      // Never upload mappings from local builds or untrusted PRs that do
      // not have the real Firebase app config. GitHub trusted main uses the
      // verified protected config installed before Gradle is invoked.
      configure<CrashlyticsExtension> {
        mappingFileUploadEnabled =
          System.getenv("GITHUB_EVENT_NAME") in setOf("push", "workflow_dispatch") &&
          System.getenv("GITHUB_REF") in setOf("refs/heads/main", "refs/heads/master") &&
          System.getenv("LANU_FIREBASE_CONFIG_VERIFIED") == "true" &&
          file("google-services.json").isFile
      }
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Stable owner key on trusted main, isolated key for PR/device test fixtures.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
    debug {
      configure<CrashlyticsExtension> { mappingFileUploadEnabled = false }
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  testBuildType = providers.gradleProperty("lanuDeviceBuildType").orNull ?: "debug"
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation("io.github.webrtc-sdk:android:150.7871.04")
  // 2.12 raises minSdk to 24; keep Android 6/API 23 support with the maintained 2.11 line.
  implementation("androidx.work:work-runtime-ktx:2.11.2")
  testImplementation("androidx.work:work-testing:2.11.2")
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation("androidx.biometric:biometric:1.2.0-alpha05")
  implementation(libs.androidx.security.crypto)
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.fragment.ktx)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // SQLCipher native library is used only by isolated shadow tests, never live Room.
  // Keep a single Zetetic version to avoid conflicting native libraries/classes.
  implementation("net.zetetic:sqlcipher-android:4.20.0")
  implementation("androidx.sqlite:sqlite:2.7.0")
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Fatal and ANR reporting must remain disabled until explicit private opt-in.
  implementation(libs.firebase.crashlytics)
  // Explicit, separately consented call and opaque message alerts; no marketing push.
  implementation("com.google.firebase:firebase-messaging")
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

