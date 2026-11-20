plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.okhttp)
    api(libs.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    // 合成した画像を作るテストは画面なしで動かす
    systemProperty("java.awt.headless", "true")
    maxHeapSize = "2g"
    // -Psamples を付けたときだけ、エミュレーターに見せる合成の画像と映像のコマを書き出す
    project.findProperty("samples")?.let { systemProperty("samples", it.toString()) }
    testLogging {
        events("failed")
        showStandardStreams = false
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
