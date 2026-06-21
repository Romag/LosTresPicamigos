package org.lostrespicamigos.domain;

public record SessionSpec(SessionMode mode, String id) {
    public SessionSpec {
        mode = mode == null ? SessionMode.NEW : mode;
        if (mode == SessionMode.RESUME && (id == null || id.isBlank())) {
            throw new IllegalArgumentException("session.id is required when session.mode is resume");
        }
    }

    public static SessionSpec fresh() {
        return new SessionSpec(SessionMode.NEW, null);
    }
}
