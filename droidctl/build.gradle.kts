import java.security.MessageDigest

plugins {
    // AGP 9 has built-in Kotlin support, so no kotlin-android plugin here.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Copies the scrcpy server built by `:server` into `:droidctl`'s assets as
 * `scrcpy-server.jar`, together with its SHA-256.
 *
 * Building the server from the sources in this repository (rather than
 * vendoring a release binary) is what lets DroidCtl claim a pinned protocol
 * version: the wire format it implements is derived from the very sources that
 * produce this artifact.
 */
abstract class PackageScrcpyServer : DefaultTask() {
    @get:InputFile
    abstract val serverApk: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun packageServer() {
        val src = serverApk.get().asFile
        val dir = outputDir.get().asFile
        dir.mkdirs()
        val jar = dir.resolve("scrcpy-server.jar")
        src.copyTo(jar, overwrite = true)

        val digest = MessageDigest.getInstance("SHA-256")
        jar.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        dir.resolve("scrcpy-server.jar.sha256")
            .writeText(digest.digest().joinToString("") { "%02x".format(it) })
    }
}

/**
 * Fails the build if the pinned scrcpy version in the version catalog stops
 * matching the version `:server` actually builds. The server refuses to start
 * when the client announces a different version, so a silent drift here would
 * only ever surface as an obscure runtime abort on the Target.
 */
abstract class CheckScrcpyVersionPin : DefaultTask() {
    @get:InputFile
    abstract val serverBuildFile: RegularFileProperty

    @get:Input
    abstract val pinnedVersion: Property<String>

    @get:OutputFile
    abstract val receipt: RegularFileProperty

    @TaskAction
    fun check() {
        val text = serverBuildFile.get().asFile.readText()
        val actual = Regex("""versionName\s+"([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: throw GradleException("Could not read versionName from ${serverBuildFile.get().asFile}")
        val pinned = pinnedVersion.get()
        if (actual != pinned) {
            throw GradleException(
                "scrcpy version pin mismatch: gradle/libs.versions.toml pins scrcpy=\"$pinned\" " +
                    "but :server builds versionName \"$actual\". Update the catalog and re-derive " +
                    "the protocol constants from the server sources (see docs/PROTOCOL.md)."
            )
        }
        receipt.get().asFile.writeText(actual)
    }
}

val scrcpyVersion = libs.versions.scrcpy.get()

val checkScrcpyVersionPin = tasks.register<CheckScrcpyVersionPin>("checkScrcpyVersionPin") {
    description = "Verifies the pinned scrcpy version matches what :server builds."
    serverBuildFile.set(rootProject.layout.projectDirectory.file("server/build.gradle"))
    pinnedVersion.set(scrcpyVersion)
    receipt.set(layout.buildDirectory.file("scrcpy-version-pin.txt"))
}

val packageScrcpyServer = tasks.register<PackageScrcpyServer>("packageScrcpyServer") {
    description = "Bundles the scrcpy server built by :server as a DroidCtl asset."
    dependsOn(":server:assembleRelease", checkScrcpyVersionPin)
    serverApk.set(
        rootProject.layout.projectDirectory
            .file("server/build/outputs/apk/release/server-release-unsigned.apk")
    )
    outputDir.set(layout.buildDirectory.dir("generated/scrcpyServer/assets"))
}

android {
    namespace = "dev.alexdev404.droidctl"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.alexdev404.droidctl"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "SCRCPY_VERSION", "\"$scrcpyVersion\"")
    }

    buildTypes {
        debug {
            // Routes MirrorSession at the connection layer to FakeScrcpyServer
            // so the whole video path can be exercised without a Target.
            // Toggled at runtime from the debug pane; this only enables the option.
            buildConfigField("boolean", "FAKE_SERVER_AVAILABLE", "true")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "FAKE_SERVER_AVAILABLE", "false")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(
            packageScrcpyServer,
            PackageScrcpyServer::outputDir
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.libsu.core)
    implementation(libs.libsu.io)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
