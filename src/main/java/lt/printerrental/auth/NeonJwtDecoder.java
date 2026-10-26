package lt.printerrental.auth;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.OctetKeyPair;
import com.nimbusds.jwt.SignedJWT;
import java.net.URI;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.HexFormat;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.*;

/** Neon uses Ed25519. Verify it with the JDK provider, and use Spring's claim validators. */
public final class NeonJwtDecoder implements JwtDecoder {
  private final SigningKeyProvider keys;
  private final OAuth2TokenValidator<Jwt> validator;

  public NeonJwtDecoder(String baseUrl, SigningKeyProvider keys) {
    var base = URI.create(baseUrl);
    var issuer = base.getScheme() + "://" + base.getRawAuthority();
    this.keys = keys;
    validator =
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer),
            jwt -> {
              boolean valid =
                  jwt.getSubject() != null
                      && !jwt.getSubject().isBlank()
                      && jwt.getSubject().length() <= 255
                      && jwt.getExpiresAt() != null
                      && jwt.getIssuedAt() != null;
              return valid
                  ? OAuth2TokenValidatorResult.success()
                  : OAuth2TokenValidatorResult.failure(
                      new OAuth2Error(
                          "invalid_token", "Required identity or expiry claim missing", null));
            });
  }

  @Override
  public Jwt decode(String token) throws JwtException {
    try {
      if (token.length() > 16384) throw new BadJwtException("Invalid token");
      var signed = SignedJWT.parse(token);
      if (!JWSAlgorithm.EdDSA.equals(signed.getHeader().getAlgorithm())
          || signed.getHeader().getKeyID() == null
          || signed.getHeader().getCriticalParams() != null)
        throw new BadJwtException("Unsupported token");
      var key = keys.findById(signed.getHeader().getKeyID());
      if (!(key instanceof OctetKeyPair okp)
          || !Curve.Ed25519.equals(okp.getCurve())
          || okp.isPrivate()) throw new BadJwtException("Unknown signing key");
      byte[] raw = okp.getX().decode();
      if (raw.length != 32) throw new BadJwtException("Invalid signing key");
      byte[] prefix = HexFormat.of().parseHex("302a300506032b6570032100");
      byte[] encoded = Arrays.copyOf(prefix, prefix.length + raw.length);
      System.arraycopy(raw, 0, encoded, prefix.length, raw.length);
      var publicKey =
          KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
      var verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(signed.getSigningInput());
      if (!verifier.verify(signed.getSignature().decode()))
        throw new BadJwtException("Invalid signature");
      var claims = signed.getJWTClaimsSet();
      var builder =
          Jwt.withTokenValue(token)
              .headers(h -> h.putAll(signed.getHeader().toJSONObject()))
              .claims(c -> c.putAll(claims.getClaims()))
              .issuedAt(claims.getIssueTime() == null ? null : claims.getIssueTime().toInstant())
              .expiresAt(
                  claims.getExpirationTime() == null
                      ? null
                      : claims.getExpirationTime().toInstant())
              .claim("aud", claims.getAudience());
      if (claims.getNotBeforeTime() != null)
        builder.notBefore(claims.getNotBeforeTime().toInstant());
      var jwt = builder.build();
      var result = validator.validate(jwt);
      if (result.hasErrors())
        throw new JwtValidationException("Invalid token claims", result.getErrors());
      return jwt;
    } catch (JwtException error) {
      throw error;
    } catch (Exception error) {
      throw new BadJwtException("Invalid token", error);
    }
  }
}
