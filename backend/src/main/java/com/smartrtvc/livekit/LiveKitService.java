package com.smartrtvc.livekit;

import static com.smartrtvc.common.Errors.fail;

import io.livekit.server.*;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class LiveKitService {
  private final String key, secret, url;
  private final RoomServiceClient client;

  public LiveKitService(
      @Value("${app.livekit-key}") String k,
      @Value("${app.livekit-secret}") String s,
      @Value("${app.livekit-url}") String internal,
      @Value("${app.livekit-public-url}") String external) {
    key = k;
    secret = s;
    url = external;
    client = RoomServiceClient.createClient(internal, k, s);
  }

  // 业务成员权限由 CallService 校验；这里仅签发限定房间和麦克风来源的凭证。
  public Map<String, String> token(String room, long id) {
    AccessToken t = new AccessToken(key, secret);
    t.setIdentity("" + id);
    // SDK 的 TTL 单位为毫秒；该有效期不是通话时长限制。
    t.setTtl(60000);
    t.addGrants(
        new RoomJoin(true),
        new RoomName(room),
        new CanPublish(true),
        new CanSubscribe(true),
        new CanPublishData(false),
        new CanPublishSources(java.util.List.of("microphone")));
    return Map.of("token", t.toJwt(), "serverUrl", url);
  }

  public void create(String room) {
    try {
      if (!client.createRoom(room).execute().isSuccessful()) throw fail(503, "媒体房间创建失败");
    } catch (java.io.IOException e) {
      throw fail(503, "媒体服务不可用");
    }
  }

  // 已不存在的房间也算清理成功，其他失败交由通话清理任务重试。
  public boolean delete(String room) {
    try {
      var response = client.deleteRoom(room).execute();
      return response.isSuccessful() || response.code() == 404;
    } catch (Exception e) {
      return false;
    }
  }

  public livekit.LivekitWebhook.WebhookEvent webhook(String body, String authorization) {
    try {
      // 保留原始 body，SDK 同时验证 JWT 签名与请求体摘要，防止伪造事件。
      return new WebhookReceiver(key, secret).receive(body, authorization);
    } catch (Exception e) {
      throw fail(401, "Webhook 签名无效");
    }
  }
}
