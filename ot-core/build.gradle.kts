plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jqwik)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()

    // Headroom for jqwik's generator state when running the property suite at high tries counts.
    maxHeapSize = "4g"

    // The harness can exist before the first property does; don't fail on an empty suite.
    failOnNoDiscoveredTests = false

    // Forward -Djqwik.* flags through to the test JVM (e.g. -Djqwik.tries.default=1000000).
    System.getProperties().forEach { (key, value) ->
        val name = key as String
        if (name.startsWith("jqwik.")) systemProperty(name, value as Any)
    }
}
