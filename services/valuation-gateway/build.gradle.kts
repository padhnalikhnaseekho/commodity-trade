plugins { `java-library` }

dependencies {
    // Only contracts and platform are allowed here; the root build fails on sibling services.
    implementation(project(":contracts"))
    implementation(project(":platform"))
}
