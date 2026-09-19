rootProject.name = "commodity-trade"

// One module per service plus shared contracts/platform.
// WHY: module dependencies are how service boundaries are enforced in the build,
// even though everything runs in one JVM for the demo (see README).
include(
    "contracts",
    "platform",
    "services:trade",
    "services:logistics",
    "services:pricing",
    "services:valuation-gateway",
    "services:blotter",
    "services:stubs",
    "benchmark",
    "demo-seed",
    "app",
)
