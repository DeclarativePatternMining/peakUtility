package org.cp26.huim.global;

public enum ProjectionBackend {
    ARRAY,
    BITSET;

    public static ProjectionBackend fromCli(String value) {
        if (value == null || value.isBlank()) {
            return ARRAY;
        }
        return ProjectionBackend.valueOf(value.trim().toUpperCase());
    }
}
