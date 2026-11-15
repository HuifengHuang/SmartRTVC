package com.smartrtvc.signaling;

import com.smartrtvc.auth.AuthService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class TicketInterceptor implements HandshakeInterceptor {
  private final AuthService auth;

  public TicketInterceptor(AuthService auth) {
    this.auth = auth;
  }

  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Map<String, Object> attrs) {
    String ticket =
        UriComponentsBuilder.fromUri(request.getURI()).build().getQueryParams().getFirst("ticket");
    // 身份来自 Redis 中的一次性 Ticket，不读取客户端传入的 userId。
    Long id = auth.consume(ticket);
    if (id == null) {
      response.setStatusCode(HttpStatus.UNAUTHORIZED);
      return false;
    }
    attrs.put("userId", id);
    attrs.put("heartbeat", System.currentTimeMillis());
    return true;
  }

  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler handler,
      Exception e) {}
}
