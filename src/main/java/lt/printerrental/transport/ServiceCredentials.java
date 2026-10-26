package lt.printerrental.transport;

public interface ServiceCredentials {
  String identityToken(String audience);

  String accessToken();
}
