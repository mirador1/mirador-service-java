package org.iris.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for in-memory brute-force protection.
 * Pure logic — no Spring context, no mocks.
 */
class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
    }

    @Test
    void unknownIp_isNotBlocked() {
        assertThat(service.isBlocked("1.2.3.4")).isFalse();
    }

    @Test
    void unknownIp_hasMaxRemainingAttempts() {
        assertThat(service.getRemainingAttempts("1.2.3.4"))
                .isEqualTo(LoginAttemptService.MAX_ATTEMPTS);
    }

    @Test
    void recordFailures_decreasesRemainingAttempts() {
        service.recordFailure("5.5.5.5");
        service.recordFailure("5.5.5.5");

        assertThat(service.getRemainingAttempts("5.5.5.5"))
                .isEqualTo(LoginAttemptService.MAX_ATTEMPTS - 2);
    }

    @Test
    void afterMaxAttempts_ipIsBlocked() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("attacker");
        }
        assertThat(service.isBlocked("attacker")).isTrue();
        assertThat(service.getRemainingAttempts("attacker")).isZero();
    }

    @Test
    void recordSuccess_clearsAttempts_andUnblocks() {
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS; i++) {
            service.recordFailure("victim");
        }
        assertThat(service.isBlocked("victim")).isTrue();

        service.recordSuccess("victim");

        assertThat(service.isBlocked("victim")).isFalse();
        assertThat(service.getRemainingAttempts("victim"))
                .isEqualTo(LoginAttemptService.MAX_ATTEMPTS);
    }

    @Test
    void ipIsolation_oneIpDoesNotAffectAnother() {
        service.recordFailure("a.b.c.d");
        assertThat(service.isBlocked("1.1.1.1")).isFalse();
    }

    @Test
    void remainingAttempts_neverNegative() {
        // rec more failures than the max
        for (int i = 0; i < LoginAttemptService.MAX_ATTEMPTS + 3; i++) {
            service.recordFailure("flood");
        }
        assertThat(service.getRemainingAttempts("flood")).isZero();
    }

    // ─── isBlocked() — uncovered branches via reflection ─────────────────────

    @Test
    void isBlocked_existingAttemptWithoutLockout_returnsFalse() throws Exception {
        // Pinned : an IP with a few failures (count > 0) but BELOW the
        // MAX_ATTEMPTS threshold has lockedUntil=null. isBlocked must
        // fall through both null-checks and return false. Without this
        // path the lockout escalation logic would silently lock
        // pre-threshold attackers if the contract drifted.
        injectStaleAttempt("partial-fail-ip", 3, null);

        assertThat(service.isBlocked("partial-fail-ip")).isFalse();
    }

    @Test
    void isBlocked_lockoutExpired_autoRemovesRecord_andReturnsFalse() throws Exception {
        // Pinned : when an IP's lockedUntil is in the past, isBlocked
        // returns false AND removes the rec from memory. Without
        // the auto-expire, locked-out clients that abandon their
        // request would leave records pinned forever — unbounded map
        // growth / DoS amplification surface.
        Instant pastLockout = Instant.now().minusSeconds(60);
        injectStaleAttempt("expired-lockout-ip", 5, pastLockout);

        assertThat(service.isBlocked("expired-lockout-ip")).isFalse();
        // The map MUST be empty for that key after the call.
        assertThat(attemptsMap()).doesNotContainKey("expired-lockout-ip");
    }

    /**
     * Inserts a synthetic AttemptRecord into the private attempts map.
     * Required because there's no public path to land an arbitrary
     * lockedUntil — recordFailure() always uses Instant.now()+15min.
     */
    private void injectStaleAttempt(String ip, int count, Instant lockedUntil) throws Exception {
        Class<?> recordClass = Class.forName("org.iris.auth.LoginAttemptService$AttemptRecord");
        Constructor<?> ctor = recordClass.getDeclaredConstructor(int.class, Instant.class, Instant.class);
        ctor.setAccessible(true);
        Object rec = ctor.newInstance(count, Instant.now(), lockedUntil);
        attemptsMap().put(ip, rec);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attemptsMap() throws Exception {
        Field f = LoginAttemptService.class.getDeclaredField("attempts");
        f.setAccessible(true);
        return (Map<String, Object>) f.get(service);
    }
}
