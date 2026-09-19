plugins {
    java
    id("org.springframework.boot") version "3.3.4"
}

// The composition root: the ONE place allowed to depend on every service module (the boundary rule only restricts :services:* from depending on
// each other). It contains no business code. Which services actually run is decided by the active Spring profiles.
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
    implementation(project(":contracts"))
    implementation(project(":platform"))
    implementation(project(":services:trade"))
    implementation(project(":services:logistics"))
    implementation(project(":services:pricing"))
    implementation(project(":services:valuation-gateway"))
    implementation(project(":services:blotter"))
    implementation(project(":services:stubs"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:redpanda")
    testImplementation(project(":demo-seed"))   // the end-to-end test runs the real seeder against the real application
}

tasks.bootRun {
    // WHY UTC: the JDBC driver sends the JVM timezone to Postgres; a legacy zone alias can fail the connection.
    jvmArgs("-Duser.timezone=UTC")
}
