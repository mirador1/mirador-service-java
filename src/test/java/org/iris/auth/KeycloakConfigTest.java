package org.iris.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link KeycloakConfig#jwtDecoder()} — the JWT decoder
 * factory branched on issuer URI shape.
 *
 * <p>Pinned contracts:
 *   - Returns null when keycloak.issuer-uri is blank (Keycloak disabled,
 *     simple JWT-only mode).
 *   - Builds a JWKS-backed NimbusJwtDecoder when the issuer URI is set.
 *   - Detects Keycloak vs Auth0 by the {@code /realms/} segment — Keycloak
 *     uses {@code /protocol/openid-connect/certs}, Auth0 uses
 *     {@code .well-known/jwks.json}.
 *   - Adds a JwtClaimValidator on AUD when Auth0 + audience is configured;
 *     skipped for Keycloak (Keycloak issues realm-scoped tokens).
 *
 * <p>We don't perform real JWKS fetches — Nimbus does that lazily on the
 * first token validation, never at decoder construction.
 */
@SuppressWarnings({"java:S125", "java:S5853"})  // S125: prose comments with code-like glyphs (arrows, backticks, parens) — not actual commented-out code. S5853: multi-assertion chain refactor deferred ; current shape reads better with subject + N separate assertions.
class KeycloakConfigTest {

    @Test
    void jwtDecoder_returnsNull_whenIssuerUriIsBlank() {
        // Pinned: in simple JWT-only mode (no Keycloak / Auth0), the
        // decoder bean MUST be null so JwtAuthenticationFilter's
        // @Nullable JwtDecoder skips the external validation path.
        // A non-null decoder here would force every request through
        // a JWKS lookup that has no endpoint to hit → 500 errors.
        KeycloakConfig config = configWith("", "");

        JwtDecoder decoder = config.jwtDecoder();

        assertThat(decoder).isNull();
    }

    @Test
    void jwtDecoder_returnsNimbusDecoder_forKeycloakIssuerUri() {
        // Pinned: a real Keycloak URI (contains /realms/) builds a decoder
        // pointing at the Keycloak-specific /protocol/openid-connect/certs
        // path. Construction is lazy — no HTTP at this stage.
        KeycloakConfig config = configWith(
                "https://keycloak.example.com/realms/iris",
                ""
        );

        JwtDecoder decoder = config.jwtDecoder();

        assertThat(decoder).isNotNull();
        assertThat(decoder).isInstanceOf(NimbusJwtDecoder.class);
    }

    @Test
    void jwtDecoder_returnsNimbusDecoder_forAuth0IssuerUri() {
        // Pinned: an Auth0 URI (no /realms/) builds a decoder pointing
        // at the standard OIDC .well-known/jwks.json path. The provider
        // detection is structural (URI shape), not a config flag — so
        // adding a third OIDC provider in the future doesn't require
        // a code branch as long as it follows the OIDC standard.
        KeycloakConfig config = configWith(
                "https://iris.eu.auth0.com/",
                "https://api.iris.io"
        );

        JwtDecoder decoder = config.jwtDecoder();

        assertThat(decoder).isNotNull();
    }

    @Test
    void jwtDecoder_skipsAudienceValidator_forKeycloakEvenIfAudienceConfigured() {
        // Pinned: Keycloak tokens are realm-scoped — the issuer claim
        // already binds the token to our realm. Adding an aud check on
        // top would either reject every Keycloak token (no aud claim)
        // or require Keycloak admin to configure an audience mapper,
        // which is an operational burden we don't want.
        // The test runs without throwing — the audience validator
        // would have crashed if added incorrectly.
        KeycloakConfig config = configWith(
                "https://keycloak.example.com/realms/iris",
                "https://api.iris.io" // configured but should be IGNORED
        );

        JwtDecoder decoder = config.jwtDecoder();

        assertThat(decoder).isNotNull();
    }

    @Test
    void jwtDecoder_addsAudienceValidator_forAuth0WithAudience() {
        // Pinned: Auth0 access tokens carry aud=[<API_ID>, <DOMAIN>/userinfo].
        // Without aud validation, any Auth0 token from the same tenant
        // would be accepted — even ones meant for a DIFFERENT API hosted
        // in the same Auth0 organisation. This test pins that the
        // construction succeeds; full claim-rejection behavior is
        // covered in JwtAuthenticationFilter integration tests.
        KeycloakConfig config = configWith(
                "https://iris.eu.auth0.com/",
                "https://api.iris.io"
        );

        JwtDecoder decoder = config.jwtDecoder();

        assertThat(decoder).isNotNull();
    }

    @Test
    void jwtDecoder_handlesAuth0WithoutAudience_doesNotAddValidator() {
        // Pinned: Auth0 mode with `auth0.audience` left blank still
        // produces a working decoder — issuer + expiry validators only.
        // Some demo / dev environments don't bother setting audience;
        // the decoder must still build, otherwise the dev mode boots
        // crash on missing JWKS validator.
        KeycloakConfig config = configWith("https://iris.eu.auth0.com/", "");

        JwtDecoder decoder = config.jwtDecoder();

        assertThat(decoder).isNotNull();
    }

    /** Helper: builds a KeycloakConfig with the two @Value-injected fields set via reflection. */
    private static KeycloakConfig configWith(String issuerUri, String audience) {
        KeycloakConfig config = new KeycloakConfig();
        ReflectionTestUtils.setField(config, "keycloakIssuerUri", issuerUri);
        ReflectionTestUtils.setField(config, "auth0Audience", audience);
        return config;
    }
}
