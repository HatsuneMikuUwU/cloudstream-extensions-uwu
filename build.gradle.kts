import com.android.build.gradle.BaseExtension
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
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0") 
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}


fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/HatsuneMikuUwU/cloudstream-extensions-uwu")
        authors = listOf("UwU")
    }


    android {
        namespace = "com.uwu"

        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }


        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations
        
        // Cloudstream dependencies
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // Other dependencies
        implementation(kotlin("stdlib")) 
        implementation("com.github.Blatzar:NiceHttp:0.4.13") 
        implementation("org.jsoup:jsoup:1.18.3") 
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0") 
        implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0") 
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1") 
        implementation("com.faendir.rhino:rhino-android:1.6.0") 
        implementation("me.xdrop:fuzzywuzzy:1.4.0") 
        implementation("com.google.code.gson:gson:2.11.0") 
        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3") 
        implementation("app.cash.quickjs:quickjs-android:0.9.2") 
        implementation("com.squareup.okhttp3:okhttp:4.12.0") 
        implementation("androidx.core:core-ktx:1.16.0") 
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1") 
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
