// Root build: shared Java 21 config, and the rule that no service module may
// depend on another service module (only on :contracts and :platform).
// WHY: cross-service reads go through contract types over HTTP/Kafka, so a
// service can be split into its own deployable later without rework.
plugins {
    java
}

allprojects {
    group = "io.commodity"
    version = "0.1.0"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
    // Spring MVC binds @PathVariable/@RequestParam by parameter name. The Spring Boot Gradle plugin adds this flag;
    // we do not use that plugin, so we add it ourselves.
    tasks.withType<JavaCompile> { options.compilerArgs.add("-parameters") }
    tasks.withType<Test> {
        useJUnitPlatform()
        // WHY UTC: the JDBC driver sends the JVM timezone to Postgres. A developer machine using a legacy
        // alias (e.g. Asia/Calcutta) makes the connection fail, and wall-clock zones make tests
        // non-deterministic. Business dates (BRD) are LocalDate, so UTC loses nothing.
        jvmArgs("-Duser.timezone=UTC")
        // Testcontainers' cleanup helper (Ryuk) is one more container that needs a random host port. On a machine whose ephemeral port range is narrow and busy
        // (WSL2 mirrored networking here) that bind can collide, and unlike our own containers it cannot be retried. It only removes containers left behind by a
        // test JVM that was killed hard; our tests stop their containers normally, so it is switched off. If a run is killed, `docker ps` shows any leftovers.
        environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    }
    dependencies {
        // Plain JUnit 5 for domain tests: no Spring, no database (project conventions rule 4).
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.10.3")
        "testImplementation"("org.assertj:assertj-core:3.26.3")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}

// Build-time boundary check. Fails the build if a service depends on a sibling service.
gradle.projectsEvaluated {
    val services = subprojects.filter { it.path.startsWith(":services:") }
    services.forEach { svc ->
        svc.configurations.forEach { cfg ->
            cfg.dependencies.withType<ProjectDependency>().forEach { dep ->
                val target = dep.dependencyProject.path
                if (target.startsWith(":services:") && target != svc.path) {
                    throw GradleException("Boundary violation: ${svc.path} depends on $target. Use :contracts instead.")
                }
            }
        }
    }
}
