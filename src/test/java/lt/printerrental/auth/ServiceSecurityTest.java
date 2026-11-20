package lt.printerrental.auth;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

/** Verifies the service's HTTP security policy for public, metering, and internal routes. */
@WebMvcTest(
    controllers = ServiceSecurityTest.Probe.class,
    properties = {
      "NEON_AUTH_BASE_URL=https://auth.test/auth",
      "NEON_AUTH_JWKS_URL=https://auth.test/jwks"
    })
@Import({
  SecurityConfiguration.class,
  InternalSecurityConfiguration.class,
  ServiceSecurityTest.Probe.class
})
class ServiceSecurityTest {
  @MockitoBean JwtDecoder decoder;
  @MockitoBean SigningKeyProvider signingKeys;
  @MockitoBean UserJwtAuthenticationConverter converter;
  @MockitoBean lt.printerrental.messaging.OutboxDispatchJob outbox;
  @Autowired MockMvc mvc;

  @Test
  void enforcesAuthenticationOnMeteringAndDeniesInternalAndForeignRoutes() throws Exception {
    mvc.perform(get("/health")).andExpect(status().isOk());

    mvc.perform(get("/api/metering/contracts/123")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/metering/contracts/123/readings")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/metering/review-queue")).andExpect(status().isUnauthorized());
    mvc.perform(post("/api/metering/policies/MODEL")).andExpect(status().isUnauthorized());

    mvc.perform(get("/api/metering/contracts/123").with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isOk());
    mvc.perform(post("/api/metering/contracts/123/readings").with(user("alice").roles("CUSTOMER")))
        .andExpect(status().isOk());
    mvc.perform(get("/api/metering/review-queue").with(user("staff").roles("EMPLOYEE")))
        .andExpect(status().isOk());
    mvc.perform(post("/api/metering/policies/MODEL").with(user("admin").roles("ADMIN")))
        .andExpect(status().isOk());

    mvc.perform(get("/api/admin/users").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());

    mvc.perform(post("/internal/events")).andExpect(status().isForbidden());
    mvc.perform(post("/internal/events").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/internal/metering/tick")).andExpect(status().isForbidden());
    mvc.perform(post("/internal/metering/tick").with(user("admin").roles("ADMIN")))
        .andExpect(status().isForbidden());
    mvc.perform(post("/internal/outbox/dispatch").with(user("employee").roles("EMPLOYEE")))
        .andExpect(status().isForbidden());
  }

  @RestController
  static class Probe {
    @RequestMapping({
      "/health",
      "/api/metering/contracts/123",
      "/api/metering/contracts/123/readings",
      "/api/metering/review-queue",
      "/api/metering/policies/MODEL",
      "/api/admin/users",
      "/internal/events",
      "/internal/metering/tick",
      "/internal/outbox/dispatch"
    })
    String respond() {
      return "ok";
    }
  }
}
