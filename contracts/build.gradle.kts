plugins { `java-library` }

// Contracts may depend on nothing beyond Jackson annotations and java.time (project rule).
// If this module ever needs Spring, the code is in the wrong module.
dependencies {
    api("com.fasterxml.jackson.core:jackson-annotations:2.17.2")
}
