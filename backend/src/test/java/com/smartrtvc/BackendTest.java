package com.smartrtvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.auth0.jwt.JWT;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrtvc.auth.*;
import com.smartrtvc.call.*;
import com.smartrtvc.livekit.*;
import com.smartrtvc.signaling.*;
import com.smartrtvc.user.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "app.jwt-secret=only-for-test-012345678901234567890123456789",
      "app.livekit-key=testkey",
      "app.livekit-secret=test-secret-012345678901234567890123456789",
      "app.livekit-url=http://localhost:7880",
      "app.livekit-public-url=ws://localhost:7880"
    })
@AutoConfigureMockMvc
class BackendTest {
  @Autowired MockMvc mvc;
  @Autowired CallService calls;
  @Autowired UserMapper users;
  @Autowired JdbcTemplate db;
  @Autowired AuthService auth;
  @Autowired ObjectMapper json;
  @MockitoBean StringRedisTemplate redis;
  @MockitoBean Signaling signaling;
  @MockitoBean LiveKitService media;
  ValueOperations<String, String> values;
  long a, b, c;

  @BeforeEach
  void setup() {
    db.update("DELETE FROM user_call");
    db.update("DELETE FROM call_session");
    db.update("DELETE FROM app_user");
    values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(signaling.online(anyLong())).thenReturn(true);
    a = user("alice");
    b = user("bob");
    c = user("carol");
  }

  long user(String name) {
    User u = new User();
    u.username = name;
    u.passwordHash = "unused";
    users.insert(u);
    return u.id;
  }

  @Test
  void authFlow() throws Exception {
    String body = "{\"username\":\"david\",\"password\":\"password123\"}";
    mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    String result =
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String token = json.readTree(result).path("data").path("token").asText();
    mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.username").value("david"));
    mvc.perform(get("/api/users/me")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/users/me").header("Authorization", "Bearer invalid"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("password123", "incorrect")))
        .andExpect(status().isUnauthorized());
    mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + token))
        .andExpect(status().isOk());
    verify(values).set(startsWith("revoked:"), eq("1"), any(java.time.Duration.class));
  }

  @Test
  void ticketsAreSingleUse() {
    when(values.getAndDelete("ticket:one")).thenReturn("" + a, null);
    assertEquals(a, auth.consume("one"));
    assertNull(auth.consume("one"));
    assertNull(auth.consume(null));
  }

  @Test
  void rejectsIllegalCalls() {
    assertThrows(Exception.class, () -> calls.invite(a, a));
    assertThrows(Exception.class, () -> calls.invite(a, 99999));
    when(signaling.online(b)).thenReturn(false);
    assertThrows(Exception.class, () -> calls.invite(a, b));
  }

  @Test
  void busyAndPermissions() {
    var call = calls.invite(a, b);
    assertThrows(Exception.class, () -> calls.invite(c, b));
    assertThrows(Exception.class, () -> calls.accept(call.id, a));
    assertThrows(Exception.class, () -> calls.token(call.id, c));
    assertThrows(Exception.class, () -> calls.hangup(call.id, c));
    assertThrows(Exception.class, () -> calls.token(call.id, a));
    assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM call_session", Integer.class));
  }

  @Test
  void transitionsAndIdempotency() {
    var call = calls.invite(a, b);
    assertEquals("CONNECTING", calls.accept(call.id, b).state);
    assertEquals("CONNECTING", calls.accept(call.id, b).state);
    verify(media, times(1)).create(call.roomName);
    when(media.webhook("a", "sig")).thenReturn(event(call, "" + a));
    when(media.webhook("b", "sig")).thenReturn(event(call, "" + b));
    calls.webhook("a", "sig");
    assertEquals("CONNECTING", calls.member(call.id, a).state);
    calls.webhook("b", "sig");
    assertEquals("ACTIVE", calls.member(call.id, a).state);
    assertEquals("ENDED", calls.hangup(call.id, a).state);
    assertEquals("ENDED", calls.hangup(call.id, b).state);
    assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM user_call", Integer.class));
    assertThrows(Exception.class, () -> calls.accept(call.id, b));
    assertNotNull(calls.invite(a, b));
  }

  private livekit.LivekitWebhook.WebhookEvent event(CallSession c, String id) {
    return livekit.LivekitWebhook.WebhookEvent.newBuilder()
        .setEvent("participant_joined")
        .setRoom(livekit.LivekitModels.Room.newBuilder().setName(c.roomName))
        .setParticipant(livekit.LivekitModels.ParticipantInfo.newBuilder().setIdentity(id))
        .build();
  }

  @Test
  void mediaToken() {
    LiveKitService real =
        new LiveKitService(
            "key",
            "secret-012345678901234567890123456789",
            "http://localhost:7880",
            "ws://localhost:7880");
    var jwt = JWT.decode(real.token("call-room", a).get("token"));
    assertEquals("" + a, jwt.getSubject());
    var grant = jwt.getClaim("video").asMap();
    assertEquals("call-room", grant.get("room"));
    assertEquals(true, grant.get("roomJoin"));
    assertEquals(false, grant.get("canPublishData"));
    assertThrows(Exception.class, () -> real.webhook("{}", "bad"));
  }

  @Test
  void concurrentInvitesHaveOnlyOneWinner() throws Exception {
    var start = new java.util.concurrent.CountDownLatch(1);
    var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var first =
          pool.submit(
              () -> {
                start.await();
                try {
                  calls.invite(a, b);
                  return true;
                } catch (Exception e) {
                  return false;
                }
              });
      var second =
          pool.submit(
              () -> {
                start.await();
                try {
                  calls.invite(c, b);
                  return true;
                } catch (Exception e) {
                  return false;
                }
              });
      start.countDown();
      assertNotEquals(
          first.get(10, java.util.concurrent.TimeUnit.SECONDS),
          second.get(10, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM call_session", Integer.class));
      assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM user_call", Integer.class));
    } finally {
      pool.shutdownNow();
    }
  }
}
