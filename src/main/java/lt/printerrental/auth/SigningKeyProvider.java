package lt.printerrental.auth;

import com.nimbusds.jose.jwk.JWK;

/** Supplies a trusted public signing key; tokens cannot choose its remote source. */
public interface SigningKeyProvider {
  JWK findById(String kid);
}
