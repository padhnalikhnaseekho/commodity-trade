plugins { `java-library` }

// Stubs stand in for services built in P1 (Business Day Control, SRD, Deal, Quality, the valuation engine).
// They implement :contracts ports or consume/produce :contracts events, so replacing one with the real service touches no caller.
// The engine adapter in particular depends on NO other service: it receives complete inputs and looks nothing up.
dependencies {
    implementation(project(":contracts"))
    implementation(project(":platform"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-context")
    implementation("org.springframework.boot:spring-boot-autoconfigure")

    testImplementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:redpanda")
}
