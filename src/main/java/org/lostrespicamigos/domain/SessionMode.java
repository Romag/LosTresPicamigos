package org.lostrespicamigos.domain;

import java.util.Locale;

public enum SessionMode {
    NEW,
    CONTINUE,
    RESUME;

    public static SessionMode parse(String value) {
        return value == null ? NEW : valueOf(value.toUpperCase(Locale.ROOT));
    }
}
