plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mkei.backcast"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mkei.backcast"
        // minSdk 26 gives the OpenAI SDK native Java 8 APIs (time, streams, Optional, NIO)
        // and matches the Android 8.0+ toolchain packages.
        minSdk = 26
        // Deliberately stays at 26: higher targets block executing the downloaded
        // toolchain binaries from app storage (API 29+), force scoped storage (30+),
        // restrict background/foreground services and cleartext HTTP.
        targetSdk = 26
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Preserve the upstream notices while merging dependency JAR resources.
            merges += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/versions/**")
        }
    }

    lint {
        // Sideloaded APK that intentionally keeps targetSdk 26 (see targetSdk above).
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.environment("BACKCAST_SCREENSHOTS", System.getenv("BACKCAST_SCREENSHOTS") ?: "")
            it.maxHeapSize = "1g"
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.openai.java)
    // App cancellation/deadline hooks use the SDK's public transport APIs directly.
    implementation(libs.okhttp)
    implementation(libs.okio.jvm)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}

// JVM regressions use the same official SDK dependency graph as the packaged app.
tasks.register("writeAgentTestClasspath") {
    val output = layout.buildDirectory.file("agent-test-classpath.txt")
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            configurations.getByName("debugRuntimeClasspath").files
                .filter { it.name.endsWith(".jar") }
                .joinToString(File.pathSeparator) { it.absolutePath }
        )
    }
}

// Symbol-level source checks use the compiler's resolved Android and SDK graph.
tasks.register("writeProductionAuditClasspath") {
    dependsOn("processDebugResources")
    val output = layout.buildDirectory.file("production-audit-classpath.txt")
    doLast {
        val compiler = tasks.named("compileDebugJavaWithJavac").get() as JavaCompile
        val entries = LinkedHashSet<File>(compiler.classpath.files)
        compiler.options.bootstrapClasspath?.let { entries.addAll(it.files) }
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(entries.joinToString(File.pathSeparator) { it.absolutePath })
    }
}
