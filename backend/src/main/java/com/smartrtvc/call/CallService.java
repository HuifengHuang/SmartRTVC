package com.smartrtvc.call;

import static com.smartrtvc.common.Errors.fail;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartrtvc.livekit.LiveKitService;
import com.smartrtvc.signaling.Signaling;
import com.smartrtvc.user.UserMapper;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;

@Service
public class CallService {
  private final CallMapper calls;
  private final UserMapper users;
  private final Signaling signal;
  private final LiveKitService media;
  private final Set<String> cleanup = ConcurrentHashMap.newKeySet();

  public CallService(CallMapper c, UserMapper u, Signaling s, LiveKitService m) {
    calls = c;
    users = u;
    signal = s;
    media = m;
  }

  public CallSession member(String id, long user) {
    CallSession c = calls.selectById(id);
    if (c == null) throw fail(404, "通话不存在");
    check(c, user);
    return c;
  }

  private void check(CallSession c, long user) {
    if (c.callerId != user && c.calleeId != user) throw fail(403, "无权访问通话");
  }

  // 数据库提交后才发送快照，防止客户端看到随后回滚的状态。
  private void publish(CallSession c, String type) {
    Runnable action =
        () -> {
          signal.send(c.callerId, type, c.id, c);
          signal.send(c.calleeId, type, c.id, c);
        };
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            public void afterCommit() {
              action.run();
            }
          });
    else action.run();
  }

  @Transactional
  public CallSession invite(long from, long to) {
    if (from == to) throw fail(400, "不能呼叫自己");
    if (users.selectById(from) == null || users.selectById(to) == null) throw fail(404, "用户不存在");
    if (!signal.online(from) || !signal.online(to)) throw fail(409, "双方必须在线");
    CallSession c = new CallSession();
    c.id = UUID.randomUUID().toString();
    c.roomName = "call-" + c.id;
    c.callerId = from;
    c.calleeId = to;
    c.state = "RINGING";
    c.callerConnected = false;
    c.calleeConnected = false;
    c.createdAt = LocalDateTime.now();
    calls.insert(c);
    // 按固定用户 ID 顺序占用双方；唯一主键阻止同一用户进入两个有效通话。
    calls.occupy(Math.min(from, to), c.id);
    calls.occupy(Math.max(from, to), c.id);
    publish(c, "CALL_INVITE");
    return c;
  }

  @Transactional
  public CallSession accept(String id, long user) {
    CallSession c = calls.lock(id);
    if (c == null) throw fail(404, "通话不存在");
    if (c.calleeId != user) throw fail(403, "只有被叫方可以接听");
    if ("CONNECTING".equals(c.state) || "ACTIVE".equals(c.state)) return c;
    if (!"RINGING".equals(c.state)) throw fail(409, "通话已结束");
    // 媒体房间创建成功后才进入 CONNECTING；重复接听在上方直接返回。
    media.create(c.roomName);
    c.state = "CONNECTING";
    c.acceptedAt = LocalDateTime.now();
    calls.updateById(c);
    publish(c, "CALL_ACCEPTED");
    return c;
  }

  @Transactional
  public CallSession hangup(String id, long user) {
    CallSession c = calls.lock(id);
    if (c == null) throw fail(404, "通话不存在");
    check(c, user);
    // 重复挂断不重复释放占用或改变结束时间。
    if (!"ENDED".equals(c.state)) {
      c.state = "ENDED";
      c.endReason = "HANGUP";
      c.endedAt = LocalDateTime.now();
      calls.updateById(c);
      calls.release(id);
      publish(c, "CALL_ENDED");
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            public void afterCommit() {
              cleanup.add(c.roomName);
            }
          });
    }
    return c;
  }

  @Transactional
  public Map<String, String> token(String id, long user) {
    CallSession c = calls.lock(id);
    if (c == null) throw fail(404, "通话不存在");
    check(c, user);
    if (!Set.of("CONNECTING", "ACTIVE").contains(c.state)) throw fail(409, "通话尚未接听或已结束");
    // 房间与身份都来自服务端已验证的数据，不接受客户端自定义。
    return media.token(c.roomName, user);
  }

  @Transactional
  public void webhook(String body, String header) {
    // 先对原始请求体验签，再根据可信事件推进业务状态。
    var event = media.webhook(body, header);
    String room = event.getRoom().getName();
    CallSession found = calls.selectOne(new QueryWrapper<CallSession>().eq("room_name", room));
    if (found == null) return;
    CallSession c = calls.lock(found.id);
    if ("ENDED".equals(c.state)) {
      cleanup.add(room);
      return;
    }
    String identity = event.getParticipant().getIdentity();
    if (!identity.equals("" + c.callerId) && !identity.equals("" + c.calleeId)) return;
    if ("participant_joined".equals(event.getEvent()) && "CONNECTING".equals(c.state)) {
      if (identity.equals("" + c.callerId)) c.callerConnected = true;
      else c.calleeConnected = true;
      // 双方连接事件都到达才确认 ACTIVE；这不等于麦克风权限已允许。
      if (Boolean.TRUE.equals(c.callerConnected) && Boolean.TRUE.equals(c.calleeConnected)) {
        c.state = "ACTIVE";
        c.activeAt = LocalDateTime.now();
      }
      calls.updateById(c);
      publish(c, "CALL_STATE_SYNC");
    }
    if ("participant_left".equals(event.getEvent())) hangup(c.id, c.callerId);
  }

  public CallSession current(long user) {
    String id = calls.current(user);
    return id == null ? null : member(id, user);
  }

  // 当前仅支持单实例。信令离线用独立事务结束通话，避免遗留用户占用。
  @EventListener
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public void offline(Signaling.Offline event) {
    String id = calls.current(event.userId());
    if (id != null) hangup(id, event.userId());
  }

  @EventListener
  public void connected(Signaling.Connected event) {
    CallSession c = current(event.userId());
    if (c != null) signal.send(event.userId(), "CALL_STATE_SYNC", c.id, c);
  }

  @EventListener(org.springframework.boot.context.event.ApplicationReadyEvent.class)
  @Transactional
  public void recover() {
    // 进程重启后内存会话已丢失，结束遗留通话而不是假设原连接仍然存在。
    for (CallSession c : calls.selectList(new QueryWrapper<CallSession>().ne("state", "ENDED")))
      hangup(c.id, c.callerId);
  }

  @Scheduled(fixedDelay = 5000)
  public void cleanup() {
    // 数据库结束与媒体删除不是同一事务：短期重复收集，失败的房间保留重试。
    for (CallSession c :
        calls.selectList(
            new QueryWrapper<CallSession>()
                .eq("state", "ENDED")
                .gt("ended_at", LocalDateTime.now().minusMinutes(2)))) cleanup.add(c.roomName);
    for (String room : List.copyOf(cleanup)) if (media.delete(room)) cleanup.remove(room);
  }
}
