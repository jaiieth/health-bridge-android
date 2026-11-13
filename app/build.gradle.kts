plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "dev.healthbridge"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.healthbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
        }
    }
    lint {
        textReport = true
        textOutput = file("build/reports/lint-results-debug.txt")
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.health.connect:connect-client:1.1.0-beta01")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    testImplementation("org.robolectric:robolectric:4.18-beta-1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val compileGapReproductions = tasks.register<JavaCompile>("compileGapReproductions") {
    val unit = tasks.named<Test>("testDebugUnitTest")
    dependsOn("compileDebugUnitTestKotlin")
    source(fileTree("src/gapReproduction/java") { include("**/*.java") })
    classpath = files(unit.get().classpath, unit.get().testClassesDirs)
    destinationDirectory.set(layout.buildDirectory.dir("classes/gapReproduction"))
    options.release.set(17)
}

listOf("gap4Reproduction" to "Gap4ReproductionTest", "gap6Reproduction" to "Gap6ReproductionTest").forEach { (taskName, suite) ->
    tasks.register<Test>(taskName) {
        val unit = tasks.named<Test>("testDebugUnitTest")
        dependsOn(compileGapReproductions)
        testClassesDirs = files(compileGapReproductions.flatMap { it.destinationDirectory })
        classpath = files(testClassesDirs, unit.get().classpath, unit.get().testClassesDirs)
        include("**/$suite.class")
        outputs.upToDateWhen { false }
    }
}
