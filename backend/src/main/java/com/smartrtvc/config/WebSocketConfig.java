package com.smartrtvc.config;

import com.smartrtvc.signaling.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
  private final TicketInterceptor ticket;
  private final Signaling handler;
  private final String origin;

  public WebSocketConfig(TicketInterceptor t, Signaling h, @Value("${app.origin}") String o) {
    ticket = t;
    handler = h;
    origin = o;
  }

  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(handler, "/ws").setAllowedOrigins(origin).addInterceptors(ticket);
  }
}
