package com.leon.workflow;

import com.example.helloservice.HelloServiceApplication;
import com.leon.workflow.dao.repository.BrowserSessionRepository;
import com.leon.workflow.service.BrowserSessionService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = HelloServiceApplication.class, properties = {
 "spring.datasource.url=jdbc:h2:mem:browserlogintest;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop",
 "leon.auth.required=true", "leon.auth.admin-origin=https://example.test",
 "leon.auth.token-sha256=1723a753ba41b0b19f3f758eb3226c453b086f5e279abe9e745e0e5534504e69"})
class BrowserLoginIntegrationTest {
 @Autowired WebApplicationContext context;
 @Autowired BrowserSessionRepository repository;
 private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).build(); }
 private String login(boolean remember) throws Exception {
  return mvc().perform(post("/auth/login").header("Origin","https://example.test")
   .header("X-Requested-With","XMLHttpRequest").contentType("application/json")
   .content("{\"token\":\"integration-test-token\",\"remember\":"+remember+"}"))
   .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
 }
 private Cookie cookie(String header) { return new Cookie(BrowserSessionService.COOKIE_NAME,header.split(";",2)[0].split("=",2)[1]); }

 @Test void rememberedCookieIsSecureAndRevocable() throws Exception {
  String header=login(true);Cookie cookie=cookie(header);
  assertTrue(header.contains("HttpOnly"));assertTrue(header.contains("Secure"));assertTrue(header.contains("SameSite=Lax"));
  assertTrue(header.contains("Max-Age=2592000"));
  assertFalse(repository.existsById(cookie.getValue()));
  assertTrue(repository.existsById(BrowserSessionService.hash(cookie.getValue())));
  mvc().perform(get("/auth/check").cookie(cookie)).andExpect(status().isNoContent());
  mvc().perform(get("/api/web-collections").cookie(cookie)).andExpect(status().isOk());
  mvc().perform(post("/api/simple-kv/delete").cookie(cookie).contentType("application/json").content("{}"))
   .andExpect(status().isForbidden());
  mvc().perform(post("/auth/logout").cookie(cookie).header("Origin","https://evil.test")
   .header("X-Requested-With","XMLHttpRequest")).andExpect(status().isForbidden());
  mvc().perform(post("/auth/logout").cookie(cookie).header("Origin","https://example.test")
   .header("X-Requested-With","XMLHttpRequest")).andExpect(status().isNoContent())
   .andExpect(header().string("Set-Cookie",org.hamcrest.Matchers.containsString("Max-Age=0")));
  mvc().perform(get("/api/web-collections").cookie(cookie)).andExpect(status().isUnauthorized());
 }

 @Test void expiredAndTamperedSessionsAreRejected() throws Exception {
  Cookie cookie=cookie(login(true));
  var saved=repository.findById(BrowserSessionService.hash(cookie.getValue())).orElseThrow();
  saved.setExpiresAt(Instant.now().minusSeconds(1));repository.save(saved);
  mvc().perform(get("/auth/check").cookie(cookie)).andExpect(status().isUnauthorized());
  mvc().perform(get("/auth/check").cookie(new Cookie(BrowserSessionService.COOKIE_NAME,"x".repeat(43)))).andExpect(status().isUnauthorized());
  Cookie rotated=cookie(login(true));
  saved=repository.findById(BrowserSessionService.hash(rotated.getValue())).orElseThrow();
  saved.setTokenFingerprint("0".repeat(64));repository.save(saved);
  mvc().perform(get("/auth/check").cookie(rotated)).andExpect(status().isUnauthorized());
 }

 @Test void sessionOnlyCookieAndLoginValidation() throws Exception {
  assertFalse(login(false).contains("Max-Age"));
  mvc().perform(post("/auth/login").header("Origin","https://evil.test").header("X-Requested-With","XMLHttpRequest")
   .contentType("application/json").content("{\"token\":\"integration-test-token\",\"remember\":true}")).andExpect(status().isForbidden());
  mvc().perform(post("/auth/login").header("Origin","https://example.test").header("X-Requested-With","XMLHttpRequest")
   .contentType("application/json").content("{\"token\":\"wrong\",\"remember\":true}")).andExpect(status().isUnauthorized());
  mvc().perform(get("/auth/check")).andExpect(status().isUnauthorized()).andExpect(header().doesNotExist("WWW-Authenticate"));
 }
}
