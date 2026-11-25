package io.strata;

/** The rule for entity type names, shared by the API and by stores that size columns from it. */
public final class EntityTypeNames {
    private EntityTypeNames() {}

    /** Longest type name. Part of the API contract (type names appear in stored keys). */
    public static final int MAX_LENGTH = 64;

    public static boolean valid(String name) {
        return name != null && name.length() <= MAX_LENGTH && name.matches("[A-Za-z][A-Za-z0-9_-]*");
    }
}
