plugins { `java-library` }

// Only :contracts and :platform are allowed as project dependencies (root build enforces this).
dependencies {
    implementation(project(":contracts"))
    implementation(project(":platform"))
}

apply(from = rootProject.file("gradle/jpa-service.gradle.kts"))
