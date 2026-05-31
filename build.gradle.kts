plugins {
    java
}

group = "com.serverdashboard"
version = "1.9.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    compileOnly("org.apache.logging.log4j:log4j-core:2.22.1")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.shredzone.acme4j:acme4j-client:3.4.0")
    // slf4j: acme4j 의존성, 로그 출력 없음 (Paper 로거 사용)
    implementation("org.slf4j:slf4j-nop:2.0.16")
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }

    jar {
        enabled = false
    }

    register<Jar>("shadowJar") {
        group = "build"
        description = "Assembles a fat JAR including runtime dependencies."
        archiveBaseName.set("ServerDashboard")
        archiveVersion.set(project.version.toString())
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE

        // 컴파일된 클래스 + 리소스
        from(sourceSets.main.get().output)
        // 런타임 의존성(Gson 등) 포함
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })

        // Gson 패키지 재배치 (ManifestTransformer 없이 직접 수행 불필요 — Paper는 자체 Gson 보유)
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    }

    build {
        dependsOn(named("shadowJar"))
    }
}
