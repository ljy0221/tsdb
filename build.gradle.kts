plugins {
    java
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(22)
        }
    }

    repositories {
        mavenCentral()
    }
}
