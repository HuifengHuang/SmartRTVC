package com.smartrtvc.signaling;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class Signaling extends TextWebSocketHandler {
  public record Offline(long userId) {}

  public record Connected(long userId) {}

  private final Map<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final StringRedisTemplate redis;
  private final ObjectMapper json;
  private final ApplicationEventPublisher events;

  public Signaling(StringRedisTemplate r, ObjectMapper j, ApplicationEventPublisher e) {
    redis = r;
    json = j;
    events = e;
  }

  public boolean online(long id) {
    return sessions.containsKey(id) && Boolean.TRUE.equals(redis.hasKey("online:" + id));
  }

  public void afterConnectionEstablished(WebSocketSession s) throws Exception {
    long id = (Long) s.getAttributes().get("userId");
    // 单用户只保留最新会话；替换后旧连接的关闭回调不能删除新会话。
    WebSocketSession old;
    synchronized (this) {
      old = sessions.put(id, s);
      redis.opsForValue().set("online:" + id, s.getId(), Duration.ofSeconds(60));
    }
    if (old != null) old.close(new CloseStatus(4001, "Another device connected"));
    events.publishEvent(new Connected(id));
  }

  public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
    long id = (Long) s.getAttributes().get("userId");
    boolean removed;
    synchronized (this) {
      removed = sessions.remove(id, s);
      if (removed) {
        try {
          redis.delete("online:" + id);
        } catch (Exception ignored) {
          /* TTL removes stale presence. */
        }
      }
    }
    // 在会话锁外发出业务事件，避免数据库操作长期占用信令管理锁。
    if (removed) events.publishEvent(new Offline(id));
  }

  public void handleTransportError(WebSocketSession s, Throwable e) throws Exception {
    s.close(CloseStatus.SERVER_ERROR);
  }

  protected void handleTextMessage(WebSocketSession s, TextMessage message) throws Exception {
    if ("ping".equals(message.getPayload())) {
      s.getAttributes().put("heartbeat", System.currentTimeMillis());
      // 同一 WebSocket 的业务推送与心跳回复需串行写入。
      synchronized (s) {
        s.sendMessage(new TextMessage("{\"type\":\"PONG\"}"));
      }
    } else {
      send(
          (Long) s.getAttributes().get("userId"),
          "ERROR",
          "",
          Map.of("message", "Unsupported signaling message"));
    }
  }

  @Scheduled(fixedDelay = 20000)
  public void heartbeat() {
    // 周期检查心跳并刷新 TTL；异常退出留下的 Redis 标记最终会过期。
    for (var entry : List.copyOf(sessions.entrySet())) {
      var s = entry.getValue();
      long last = (long) s.getAttributes().getOrDefault("heartbeat", System.currentTimeMillis());
      if (System.currentTimeMillis() - last > 60000) {
        close(entry.getKey());
        continue;
      }
      synchronized (this) {
        if (sessions.get(entry.getKey()) == s)
          redis.opsForValue().set("online:" + entry.getKey(), s.getId(), Duration.ofSeconds(60));
      }
    }
  }

  public void send(long user, String type, String callId, Object data) {
    WebSocketSession s = sessions.get(user);
    if (s == null || !s.isOpen()) return;
    try {
      synchronized (s) {
        s.sendMessage(
            new TextMessage(
                json.writeValueAsString(
                    Map.of(
                        "eventId",
                        UUID.randomUUID().toString(),
                        "type",
                        type,
                        "callId",
                        callId,
                        "timestamp",
                        System.currentTimeMillis(),
                        "data",
                        data))));
      }
    } catch (Exception e) {
      close(user);
    }
  }

  public void close(long id) {
    WebSocketSession s = sessions.get(id);
    if (s != null)
      try {
        s.close();
        afterConnectionClosed(s, CloseStatus.NORMAL);
      } catch (Exception ignored) {
        afterConnectionClosed(s, CloseStatus.SERVER_ERROR);
      }
  }
}
