package io.strata;

/**
 * A main class for apps that are only entities: finds every @Entity (and entity listed in
 * config), serves their routes and the management API, and stops gracefully on shutdown.
 * The Gradle plugin uses it when the app doesn't name its own main class.
 *
 * <pre>
 * java -cp app.jar io.strata.StrataMain --strata.http.port=9000
 * </pre>
 */
public final class StrataMain {
    private StrataMain() {}

    public static void main(String[] args) {
        Strata.run(args);
    }
}
