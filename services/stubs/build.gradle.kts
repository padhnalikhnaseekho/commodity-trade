plugins { `java-library` }

// Stubs stand in for services built in P1 (Business Day Control, SRD, Deal, Quality, engine).
// They implement :contracts ports, so replacing one with the real service touches no caller.
dependencies {
    implementation(project(":contracts"))
    implementation(project(":platform"))
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.3.4"))
    implementation("org.springframework:spring-web")
    implementation("org.springframework:spring-context")
}
