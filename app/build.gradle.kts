import com.google.protobuf.gradle.proto

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.protobuf)
}

android {
    namespace = "app.somasafe"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.somasafe"
        minSdk = 34
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                targets("somasafe_ml")
                arguments(
                    "-DCMAKE_EXPORT_COMPILE_COMMANDS=ON",
                    "-DLITERT_PLATFORM_DIR=android_arm64",
                    "-DLITERT_ACCELERATOR_NAME=libLiteRtClGlAccelerator.so",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // jdsp's transitive jars (pdfbox/fontbox/ssj and stray junit) ship duplicate
    // license/notice metadata that collides on resource merge; none is needed in the APK.
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.md",
            )
        }
    }

    // The capture-import schema is the shared protobuf (symlinked at ../shared).
    sourceSets {
        getByName("main") {
            proto { srcDir("${rootProject.projectDir}/shared") }
        }
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    defaultConfig {
        val backendUrl = project.findProperty("backend.url")
            ?: throw GradleException(
                "backend.url is not set. Define it in gradle.properties (e.g. backend.url=http://10.0.0.5:8000).",
            )
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")
    }
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}" }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // Android wants the lite runtime; java-lite keeps the generated code small.
                create("java") { option("lite") }
            }
        }
    }
}

dependencies {
    implementation(libs.guava)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.coroutines.android)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.security.crypto)

    implementation(libs.jdsp) {
        // jdsp -> wavfile pulls the maven-surefire test harness onto the runtime
        // classpath (duplicate classes that break the APK build); it is test-only.
        exclude(group = "org.junit.platform", module = "junit-platform-surefire-provider")
    }

    implementation(libs.protobuf.javalite)

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
