package lt.printerrental.auth;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.oauth2.jwt.JwtException;

class NeonJwtDecoderTest {
  HttpServer server;
  KeyPair key;
  NeonJwtDecoder decoder;
  ObjectMapper mapper = new ObjectMapper();
  String jwks;

  @BeforeEach
  void start() throws Exception {
    key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    jwks = keys(key, "key-1");
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/jwks",
        e -> {
          var bytes = jwks.getBytes(java.nio.charset.StandardCharsets.UTF_8);
          e.sendResponseHeaders(200, bytes.length);
          e.getResponseBody().write(bytes);
          e.close();
        });
    server.start();
    decoder =
        new NeonJwtDecoder(
            "https://auth.example.test/auth",
            new RemoteJwksSigningKeyProvider(
                java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks"),
                java.net.http.HttpClient.newHttpClient(),
                java.time.Clock.systemUTC()));
  }

  @Test
  void malformedTokenIsAnInvalidBearerTokenRatherThanAServerFailure() {
    var provider =
        new org.springframework.security.oauth2.server.resource.authentication
            .JwtAuthenticationProvider(decoder);
    assertThatThrownBy(
            () ->
                provider.authenticate(
                    new org.springframework.security.oauth2.server.resource.authentication
                        .BearerTokenAuthenticationToken("invalid")))
        .isInstanceOf(
            org.springframework.security.oauth2.server.resource.InvalidBearerTokenException.class);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  String b64(byte[] value) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  String keys(KeyPair pair, String kid) {
    var encoded = pair.getPublic().getEncoded();
    return "{\"keys\":[{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"kid\":\""
        + kid
        + "\",\"x\":\""
        + b64(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length))
        + "\"}]}";
  }

  Map<String, Object> claims() {
    return new HashMap<>(
        Map.of(
            "sub",
            "customer-a",
            "iss",
            "https://auth.example.test",
            "iat",
            Instant.now().getEpochSecond(),
            "exp",
            Instant.now().plusSeconds(900).getEpochSecond(),
            "email",
            "a@example.test"));
  }

  String token(Map<String, Object> claims, String alg, String kid, KeyPair signingKey)
      throws Exception {
    var input =
        b64(mapper.writeValueAsBytes(Map.of("alg", alg, "kid", kid)))
            + "."
            + b64(mapper.writeValueAsBytes(claims));
    var signer = Signature.getInstance("Ed25519");
    signer.initSign(signingKey.getPrivate());
    signer.update(input.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    return input + "." + b64(signer.sign());
  }

  @Test
  void validatesRealEd25519AndCachesKeys() throws Exception {
    var jwt = token(claims(), "EdDSA", "key-1", key);
    assertThat(decoder.decode(jwt).getSubject()).isEqualTo("customer-a");
    server.stop(0);
    assertThat(decoder.decode(jwt).getClaimAsString("email")).isEqualTo("a@example.test");
  }

  @Test
  void rejectsForgedSignature() throws Exception {
    var forged =
        token(
            claims(), "EdDSA", "key-1", KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    assertThatThrownBy(() -> decoder.decode(forged)).isInstanceOf(JwtException.class);
  }

  @Test
  void rejectsIssuerExpiryFutureAndMissingIdentity() throws Exception {
    for (var field : List.of("iss", "exp", "nbf", "sub")) {
      var c = claims();
      switch (field) {
        case "iss" -> c.put(field, "https://attacker.test");
        case "exp" -> c.put(field, Instant.now().minusSeconds(120).getEpochSecond());
        case "nbf" -> c.put(field, Instant.now().plusSeconds(300).getEpochSecond());
        case "sub" -> c.remove(field);
      }
      var token = token(c, "EdDSA", "key-1", key);
      assertThatThrownBy(() -> decoder.decode(token)).as(field).isInstanceOf(JwtException.class);
    }
  }

  @Test
  void rejectsUnknownKeyAlgorithmAndMalformedToken() throws Exception {
    for (var args : List.of(new String[] {"HS256", "key-1"}, new String[] {"EdDSA", "other"})) {
      var token = token(claims(), args[0], args[1], key);
      assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }
    assertThatThrownBy(() -> decoder.decode("invalid")).isInstanceOf(JwtException.class);
  }

  @Test
  void refreshesRotatedKeysWithoutFetchingOnEveryUnknownKey() throws Exception {
    var clock = org.mockito.Mockito.mock(java.time.Clock.class);
    var now = Instant.now();
    org.mockito.Mockito.when(clock.instant()).thenReturn(now);
    var provider =
        new RemoteJwksSigningKeyProvider(
            java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks"),
            java.net.http.HttpClient.newHttpClient(),
            clock);
    decoder = new NeonJwtDecoder("https://auth.example.test/auth", provider);
    decoder.decode(token(claims(), "EdDSA", "key-1", key));
    var rotated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    jwks = keys(rotated, "key-2");
    var nextToken = token(claims(), "EdDSA", "key-2", rotated);
    assertThatThrownBy(() -> decoder.decode(nextToken)).isInstanceOf(JwtException.class);
    org.mockito.Mockito.when(clock.instant()).thenReturn(now.plusSeconds(11));
    assertThat(decoder.decode(nextToken).getSubject()).isEqualTo("customer-a");
  }
}
