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
            val unit = it
            unit.systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
            unit.exclude("**/RoomCrashProcess\$Child.class")
            unit.doFirst { unit.systemProperty("hb.test.runtime.classpath", unit.classpath.asPath) }
            providers.environmentVariable("HB_ROBOLECTRIC_SDK_DIR").orNull?.let { directory ->
                unit.systemProperty("robolectric.offline", "true")
                unit.systemProperty("robolectric.dependency.dir", directory)
            }
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

val gapFixtureClasses = layout.buildDirectory.dir("classes/gapFixtures")
val compileGapFixtures = tasks.register<JavaExec>("compileGapFixtures") {
    val unit = tasks.named<Test>("testDebugUnitTest")
    dependsOn("compileDebugUnitTestKotlin")
    classpath = configurations.getByName("kotlinCompilerClasspath")
    mainClass.set("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler")
    val sources = fileTree("src/gapReproduction/kotlin") { include("**/*.kt") }
    inputs.files(sources, unit.map { it.classpath }, unit.map { it.testClassesDirs })
    outputs.dir(gapFixtureClasses)
    doFirst {
        val dependencies = files(unit.get().classpath, unit.get().testClassesDirs)
        setArgs(listOf("-no-stdlib", "-no-reflect", "-Xskip-metadata-version-check", "-jvm-target", "17",
            "-classpath", dependencies.asPath,
            "-Xfriend-paths=" + dependencies.files.joinToString(","),
            "-d", gapFixtureClasses.get().asFile.path) + sources.files.map { it.path })
    }
}

val compileGapReproductions = tasks.register<JavaCompile>("compileGapReproductions") {
    val unit = tasks.named<Test>("testDebugUnitTest")
    dependsOn(compileGapFixtures)
    source(fileTree("src/gapReproduction/java") { include("**/*.java") })
    classpath = files(unit.get().classpath, unit.get().testClassesDirs, gapFixtureClasses)
    destinationDirectory.set(layout.buildDirectory.dir("classes/gapReproduction"))
    options.release.set(17)
}

listOf("gap4Reproduction" to "Gap4ReproductionTest", "gap6Reproduction" to "Gap6ReproductionTest").forEach { (taskName, suite) ->
    tasks.register<Test>(taskName) {
        val unit = tasks.named<Test>("testDebugUnitTest")
        dependsOn(compileGapReproductions)
        testClassesDirs = files(compileGapReproductions.flatMap { it.destinationDirectory })
        classpath = files(testClassesDirs, unit.get().classpath, unit.get().testClassesDirs, gapFixtureClasses)
        include("**/$suite.class")
        outputs.upToDateWhen { false }
    }
}
