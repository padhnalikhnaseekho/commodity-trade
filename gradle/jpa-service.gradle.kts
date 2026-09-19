// Shared dependencies for services that own a Postgres schema (JPA + Flyway + Testcontainers).
// WHY: one place to bump versions; each service still declares only :contracts and :platform
// as project dependencies, so the boundary rule in the root build stays checkable.
val bootVersion = "3.3.4"
// WHY override: Docker 29 rejects API < 1.40, and the Testcontainers version managed by Boot 3.3.4
// (1.19.x) speaks API 1.32, so every container test fails with "client version 1.32 is too old".
val testcontainersVersion = "1.21.4"

dependencies {
    "implementation"(platform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
    "implementation"("org.springframework.boot:spring-boot-starter-data-jpa")
    "implementation"("org.springframework.boot:spring-boot-starter-web")
    "implementation"("org.flywaydb:flyway-core")
    "implementation"("org.flywaydb:flyway-database-postgresql")
    "runtimeOnly"("org.postgresql:postgresql")

    "testImplementation"(platform("org.springframework.boot:spring-boot-dependencies:$bootVersion"))
    "testImplementation"(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    "testImplementation"("org.springframework.boot:spring-boot-starter-test")
    "testImplementation"("org.springframework.boot:spring-boot-testcontainers")
    "testImplementation"("org.testcontainers:junit-jupiter")
    "testImplementation"("org.testcontainers:postgresql")
}
