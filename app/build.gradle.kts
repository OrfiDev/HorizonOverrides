plugins { id("com.android.application") }

layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("app"))

android {
    namespace = "com.quest.horizonconfig"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.quest.horizonconfig"
        minSdk = 29
        targetSdk = 36
        versionCode = providers.gradleProperty("moduleVersionCode").get().toInt()
        versionName = "1.0.$versionCode"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.android.material:material:1.14.0")
    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("io.github.libxposed:service:102.0.0")
}
