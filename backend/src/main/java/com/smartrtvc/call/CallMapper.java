package com.smartrtvc.call;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;

@Mapper
public interface CallMapper extends BaseMapper<CallSession> {
  // 必须在事务中调用：锁住通话记录，串行处理接听、挂断和媒体事件。
  @Select("SELECT * FROM call_session WHERE id=#{id} FOR UPDATE")
  CallSession lock(String id);

  // user_id 的唯一约束是最终并发防线；冲突时整个通话创建事务回滚。
  @Insert("INSERT INTO user_call(user_id,call_id) VALUES(#{user},#{call})")
  void occupy(@Param("user") long user, @Param("call") String call);

  // 结束通话时释放双方占用，允许双方再次发起新的呼叫。
  @Delete("DELETE FROM user_call WHERE call_id=#{call}")
  void release(String call);

  @Select("SELECT call_id FROM user_call WHERE user_id=#{user}")
  String current(long user);
}
