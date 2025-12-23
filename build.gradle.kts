import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import com.android.build.gradle.BaseExtension

buildscript {
    repositories {
        google()
        mavenCentral()
        // Repository JitPack untuk tools dan dependensi
        maven("https://jitpack.io")
    }

    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2") // Versi AGP yang stabil
        // FIX: Tambahkan 'master-' sebelum SNAPSHOT
        classpath("com.github.recloudstream:gradle:master-SNAPSHOT")
        // Disarankan Kotlin 1.9.23 agar kompatibel dengan Cloudstream saat ini
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.23")
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
        // Jika berjalan di GitHub workflow, gunakan variabel environment repository
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/hexated/cloudstream-extensions-hexated")
        authors = listOf("Hexated")
    }

    android {
        namespace = "com.hexated"

        defaultConfig {
            minSdk = 21
            // FIX: Gunakan sintaks property, bukan method
            compileSdk = 35 
            targetSdk = 35
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }

        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
            kotlinOptions {
                jvmTarget = "1.8"
                freeCompilerArgs = freeCompilerArgs +
                        "-Xno-call-assertions" +
                        "-Xno-param-assertions" +
                        "-Xno-receiver-assertions"
            }
        }
    }

    dependencies {
        // FIX: Gunakan 'compileOnly' atau 'cloudstream' configuration secara eksplisit
        // untuk menghindari error "Configuration not found"
        add("cloudstream", "com.lagradost:cloudstream3:pre-release")

        implementation(kotlin("stdlib")) 
        implementation("com.github.Blatzar:NiceHttp:0.4.13")
        implementation("org.jsoup:jsoup:1.18.3")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
        implementation("io.karn:khttp-android:0.1.2")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
        implementation("org.mozilla:rhino:1.7.14") 
    }
}

// Task clean standar
tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
