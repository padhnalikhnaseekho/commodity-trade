plugins { `java-library` }

// Only :contracts and :platform are allowed as project dependencies (root build enforces this).
dependencies {
    implementation(project(":contracts"))
    implementation(project(":platform"))
    implementation("com.github.ben-manes.caffeine:caffeine")   // LEGACY/TARGET: in-process cache stands in for the distributed cache of the target design
}

apply(from = rootProject.file("gradle/jpa-service.gradle.kts"))
