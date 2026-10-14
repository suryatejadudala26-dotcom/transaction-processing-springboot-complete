package com.example.transactions.util;

/** Small normalization helpers shared by request and persistence logic. */
public final class TransactionRequestUtils {
    private TransactionRequestUtils() { }

    public static String normalizeOptionalId(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
