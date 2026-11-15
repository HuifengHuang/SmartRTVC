package com.smartrtvc.call;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;

@TableName("call_session")
public class CallSession {
  @TableId(type = IdType.INPUT)
  public String id;

  public Long callerId;
  public Long calleeId;
  public String roomName;
  public String state;
  public Boolean callerConnected;
  public Boolean calleeConnected;
  public String endReason;
  public LocalDateTime createdAt;
  public LocalDateTime acceptedAt;
  public LocalDateTime activeAt;
  public LocalDateTime endedAt;
}
