package org.apache.iotdb;

import org.apache.iotdb.service.Table1Service;
import org.apache.iotdb.service.Table2Service;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class ApplicationTest {
  @Autowired private Table1Service table1Service;
  @Autowired private Table2Service table2Service;

  @Test
  void contextLoads() {
    // 启动Spring容器，验证主流程无异常
    System.out.println("Table1 查询结果：" + table1Service.list());
    System.out.println("Table2 查询结果：" + table2Service.list());
  }
}
