package io.commodity.seed;

/** Command line entry: {@code ./gradlew :demo-seed:run [--args='http://host:8080 42']} (base URL, then the random seed). */
public final class SeedMain {

    private SeedMain() {}

    public static void main(String[] args) {
        String base = args.length > 0 ? args[0] : "http://localhost:8080";
        long seed = args.length > 1 ? Long.parseLong(args[1]) : 42L;
        System.out.println("Seeding " + base + " (seed " + seed + ") ...");
        Seeder.Summary s = new Seeder(base, seed).run();
        System.out.printf("Created %d trades, %d quotas, %d assignments, %d parameters, %d price components, %d approvals; requested %d valuations.%n",
                s.trades(), s.quotas(), s.assignments(), s.parameters(), s.components(), s.approvals(), s.valuationsRequested());
    }
}
