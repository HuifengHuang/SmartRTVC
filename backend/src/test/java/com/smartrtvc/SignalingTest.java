package com.smartrtvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartrtvc.auth.AuthService;
import com.smartrtvc.signaling.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.*;
import org.springframework.http.server.*;
import org.springframework.web.socket.*;

class SignalingTest {
  @Test
  void rejectsUnauthorizedAndBindsTrustedIdentity() {
    var auth = mock(AuthService.class);
    when(auth.consume("one")).thenReturn(null);
    var interceptor = new TicketInterceptor(auth);
    var request = mock(ServerHttpRequest.class);
    var response = mock(ServerHttpResponse.class);
    when(request.getURI()).thenReturn(URI.create("http://localhost/ws?ticket=one&userId=999"));
    Map<String, Object> attrs = new HashMap<>();
    assertFalse(interceptor.beforeHandshake(request, response, null, attrs));
    when(auth.consume("one")).thenReturn(7L);
    assertTrue(interceptor.beforeHandshake(request, response, null, attrs));
    assertEquals(7L, attrs.get("userId"));
  }

  @Test
  void sendsAndReplacesSessions() throws Exception {
    var redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    var events = mock(ApplicationEventPublisher.class);
    var signaling = new Signaling(redis, new ObjectMapper(), events);
    var first = session("first");
    var second = session("second");
    signaling.afterConnectionEstablished(first);
    signaling.send(7, "CALL_INVITE", "call", Map.of("id", "call"));
    verify(first)
        .sendMessage(
            argThat(
                m ->
                    m instanceof TextMessage
                        && ((TextMessage) m).getPayload().contains("CALL_INVITE")));
    signaling.afterConnectionEstablished(second);
    verify(first).close(any(CloseStatus.class));
    signaling.afterConnectionClosed(first, CloseStatus.NORMAL);
    verify(redis, never()).delete("online:7");
    signaling.afterConnectionClosed(second, CloseStatus.NORMAL);
    verify(redis).delete("online:7");
  }

  private WebSocketSession session(String id) {
    var session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(id);
    when(session.isOpen()).thenReturn(true);
    when(session.getAttributes()).thenReturn(new HashMap<>(Map.of("userId", 7L)));
    return session;
  }
}
