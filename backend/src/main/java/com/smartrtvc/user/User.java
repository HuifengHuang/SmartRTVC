package com.smartrtvc.user;

import com.baomidou.mybatisplus.annotation.*;
import com.fasterxml.jackson.annotation.JsonIgnore;

@TableName("app_user")
public class User {
  @TableId(type = IdType.AUTO)
  public Long id;

  public String username;
  @JsonIgnore public String passwordHash;
}
