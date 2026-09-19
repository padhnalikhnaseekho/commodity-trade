plugins { application }

// The demo book generator. It drives a RUNNING application purely over its HTTP API (it depends on no service module), so it exercises exactly what a user
// or a client system would: `./gradlew :demo-seed:run` (optionally with --args='http://host:8080').
application {
    mainClass = "io.commodity.seed.SeedMain"
    applicationDefaultJvmArgs = listOf("-Duser.timezone=UTC")
}

dependencies {
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    runtimeOnly("ch.qos.logback:logback-classic:1.5.8")
}
