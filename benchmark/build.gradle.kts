plugins { application }

// The benchmark harness: not a service, so it may depend on a service module (the boundary rule only restricts
// :services:* modules from depending on each other). It starts its own Postgres with Testcontainers, so
// `./gradlew :benchmark:run` needs only Docker.
application {
    mainClass = "io.commodity.benchmark.StructuralSharingBenchmark"
    // WHY UTC: the JDBC driver sends the JVM timezone to Postgres; a legacy zone alias can fail the connection.
    applicationDefaultJvmArgs = listOf("-Duser.timezone=UTC")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
    implementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    implementation(project(":services:pricing"))
    implementation(project(":contracts"))
    implementation(project(":platform"))
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.testcontainers:postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("ch.qos.logback:logback-classic")
}

tasks.named<JavaExec>("run") {
    // see the root build: the cleanup helper container can fail to bind a host port on a busy, narrow ephemeral range
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
}
