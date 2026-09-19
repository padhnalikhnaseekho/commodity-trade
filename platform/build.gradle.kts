plugins { `java-library` }

val bootVersion = "3.3.4"
// See gradle/jpa-service.gradle.kts for why Testcontainers is pinned above the Boot-managed version.
val testcontainersVersion = "1.21.4"

// Cross-cutting infrastructure only (outbox, eventing, error shape). No domain concepts here.
dependencies {
    api(project(":contracts"))
    api(platform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
    api("org.springframework:spring-jdbc")
    api("org.springframework:spring-tx")
    api("org.springframework:spring-web")
    api("org.springframework.kafka:spring-kafka")
    api("org.slf4j:slf4j-api")
    api("org.flywaydb:flyway-core")
    api("org.flywaydb:flyway-database-postgresql")
    api("com.fasterxml.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:redpanda")
    testImplementation("org.springframework:spring-test")
    testRuntimeOnly("org.postgresql:postgresql")
    testRuntimeOnly("ch.qos.logback:logback-classic")
}
