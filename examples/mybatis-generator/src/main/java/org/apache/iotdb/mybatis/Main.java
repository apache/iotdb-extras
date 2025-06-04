package org.apache.iotdb.mybatis;

import org.apache.iotdb.mybatis.plugin.mapper.MixMapper;
import org.apache.iotdb.mybatis.plugin.model.Mix;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class Main {
  public static void main(String[] args) throws IOException {
    String resource = "mybatis-config.xml";
    InputStream inputStream = Resources.getResourceAsStream(resource);
    SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(inputStream);

    try (SqlSession session = sqlSessionFactory.openSession(true)) {
      MixMapper mapper = session.getMapper(MixMapper.class);

      Date now = new Date();

      // 1. insertOne
      System.out.println("1. insertOne...");
      insertOne(mapper, new Date(1));
      insertOne(mapper, new Date(2));
      insertOne(mapper, new Date(3));
      System.out.println("------------------\n");

      // 2. selectAll
      System.out.println("2. selectAll...");
      selectAll(mapper);
      System.out.println("------------------\n");

      // 3. selectByPrimaryKey
      System.out.println("3. selectByPrimaryKey...");
      selectByPrimaryKey(mapper, new Date(1), "dev001");
      System.out.println("------------------\n");

      // 4. deleteByPrimaryKey
      System.out.println("4. deleteByPrimaryKey...");
      deleteByPrimaryKey(mapper, new Date(1), "dev001");
      deleteByPrimaryKey(mapper, new Date(2), "dev001");
      System.out.println("selectAll...");
      selectAll(mapper);
      System.out.println("------------------\n");

      // 5. batchInsert
      System.out.println("5. batchInsert...");
      batchInsert(mapper, new Date(6));
      System.out.println("selectAll...");
      selectAll(mapper);
      System.out.println("------------------\n");
    }
  }

  private static void insertOne(MixMapper mapper, Date now) {
    Mix mix = new Mix();
    mix.setTime(now);
    mix.setDeviceId("dev001");
    mix.setRegion("华东");
    mix.setPlantId("plantA");
    mix.setModelId("modelX");
    mix.setMaintenance("正常");
    mix.setTemperature(25.5);
    mix.setHumidity(60.0);
    mix.setStatus(true);
    mix.setArrivalTime(now);
    mapper.insert(mix);
  }

  private static void selectAll(MixMapper mapper) {
    List<Mix> all = mapper.selectAll();
    all.forEach(System.out::println);
  }

  private static void selectByPrimaryKey(MixMapper mapper, Date time, String deviceId) {
    Mix one = mapper.selectByPrimaryKey(time, deviceId);
    System.out.println("results: " + one);
  }

  private static void deleteByPrimaryKey(MixMapper mapper, Date time, String deviceId) {
    mapper.deleteByPrimaryKey(time, deviceId);
  }

  private static void batchInsert(MixMapper mapper, Date now) {
    List<Mix> batchList = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      Mix m = new Mix();
      m.setTime(new Date(now.getTime() + i * 1000));
      m.setDeviceId("dev00" + (i + 2));
      m.setRegion("华东");
      m.setPlantId("plantA");
      m.setModelId("modelX");
      m.setMaintenance("正常");
      m.setTemperature(20.0 + i);
      m.setHumidity(50.0 + i);
      m.setStatus(i % 2 == 0);
      m.setArrivalTime(new Date(now.getTime() + i * 1000));
      batchList.add(m);
    }
    mapper.batchInsert(batchList);
  }
}
