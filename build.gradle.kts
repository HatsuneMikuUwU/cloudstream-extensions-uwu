import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:9.2.1")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) =
    extensions.getByType<CloudstreamExtension>().configuration()

fun Project.android(configuration: LibraryExtension.() -> Unit) =
    extensions.getByType<LibraryExtension>().configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/HatsuneMikuUwU/cloudstream-extensions-uwu")
        authors = listOf("Miku")
    }

    android {
        namespace = "com.miku"
        compileSdk = 35

        defaultConfig {
            minSdk = 21
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_18
            targetCompatibility = JavaVersion.VERSION_18
        }

        tasks.withType<KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_18)

                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        add("cloudstream", "com.lagradost:cloudstream3:pre-release")
        
        // Kotlin & Coroutines
        add("implementation", kotlin("stdlib"))
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        add("implementation", "org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        
        // Network & Scraping
        add("implementation", "com.github.Blatzar:NiceHttp:0.4.18")
        add("implementation", "com.squareup.okhttp3:okhttp:5.4.0")
        add("implementation", "org.jsoup:jsoup:1.22.2")
        
        // JSON Parsing
        add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
        add("implementation", "com.fasterxml.jackson.core:jackson-databind:2.22.1")
        add("implementation", "com.google.code.gson:gson:2.14.0")
        
        // JavaScript Evaluation
        add("implementation", "com.faendir.rhino:rhino-android:1.6.0")
        add("implementation", "app.cash.quickjs:quickjs-android:0.9.2")
        
        // Utils & Core
        add("implementation", "me.xdrop:fuzzywuzzy:1.4.0")
        add("implementation", "androidx.core:core-ktx:1.19.0")
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
