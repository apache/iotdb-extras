/**
 * Copyright From 2025, IoTDB.
 *
 * <p>MixMapper.java
 */
package org.apache.iotdb.mybatis.plugin.mapper;

import org.apache.iotdb.mybatis.plugin.model.Mix;

import org.apache.ibatis.annotations.Param;

import java.util.Date;
import java.util.List;

public interface MixMapper {
  int deleteByPrimaryKey(@Param("time") Date time, @Param("deviceId") String deviceId);

  int insert(Mix row);

  Mix selectByPrimaryKey(@Param("time") Date time, @Param("deviceId") String deviceId);

  List<Mix> selectAll();

  int batchInsert(@Param("records") List<Mix> records);
}
