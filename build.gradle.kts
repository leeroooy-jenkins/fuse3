plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly("org.projectlombok:lombok:1.18.46")
    annotationProcessor("org.projectlombok:lombok:1.18.46")

    implementation("org.cryptomator:jfuse:0.7.3")
    implementation("ch.qos.logback:logback-classic:1.5.19")

    // AWS SDK v2 говорит с любым S3-совместимым хранилищем (MinIO/Silo в том числе).
    // Штатные http-клиенты (apache, netty) не нужны: берём самый лёгкий, на HttpURLConnection.
    implementation("software.amazon.awssdk:s3:2.55.1") {
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation("software.amazon.awssdk:url-connection-client:2.55.1")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.shadowJar {
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    manifest {
        attributes(
            "Main-Class" to "fuse.Main",
            "Enable-Native-Access" to "ALL-UNNAMED"
        )
    }
}
