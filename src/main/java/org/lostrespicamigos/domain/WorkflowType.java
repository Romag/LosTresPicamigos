package org.lostrespicamigos.domain;

import java.util.Locale;

public enum WorkflowType {
    REVIEW_PANEL,
    PLAN_IMPLEMENT_REVIEW;

    public static WorkflowType parse(String value) {
        if (value == null) throw new IllegalArgumentException("workflow type is required");
        return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
