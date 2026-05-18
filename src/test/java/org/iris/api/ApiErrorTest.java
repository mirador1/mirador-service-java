package org.iris.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ApiError} — a Java record DTO used as a lightweight
 * alternative to Spring's ProblemDetail for error responses.
 *
 * <p>Records get equals/hashCode/toString auto-generated. These tests
 * lock in the contract so any future regression (e.g. switching record
 * → class without overrides) breaks the build.
 *
 * <p>Coverage : was 0% before this commit ; complete the
 * {@code org.iris.api} package to 100%.
 */
@SuppressWarnings("java:S5853")  // Multi-assertion chain refactor deferred ; current shape reads better when subject + N separate assertions.
class ApiErrorTest {

    @Test
    void exposesCodeAndMessageViaRecordAccessors() {
        ApiError err = new ApiError("RATE_LIMIT_EXCEEDED", "Too many requests, retry after 10s");
        assertThat(err.code()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(err.message()).isEqualTo("Too many requests, retry after 10s");
    }

    @Test
    void implementsValueEquality() {
        ApiError a = new ApiError("X", "y");
        ApiError b = new ApiError("X", "y");
        ApiError c = new ApiError("X", "z");
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }

    @Test
    void toStringIncludesBothFields() {
        ApiError err = new ApiError("NOT_FOUND", "no such customer");
        // The JDK's default record toString is `ApiError[code=NOT_FOUND, message=no such customer]`
        assertThat(err.toString())
            .contains("NOT_FOUND")
            .contains("no such customer");
    }

    @Test
    void allowsNullFields() {
        // Records don't enforce non-null without a custom constructor.
        // Confirms the record stays liberal — caller decides whether
        // to wrap nulls. Locks the current contract.
        ApiError err = new ApiError(null, null);
        assertThat(err.code()).isNull();
        assertThat(err.message()).isNull();
    }
}
