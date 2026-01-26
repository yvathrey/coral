/**
 * Copyright 2026 LinkedIn Corporation. All rights reserved.
 * Licensed under the BSD-2 Clause license.
 * See LICENSE in the project root for license information.
 */
package com.linkedin.coral.materializedview;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.apache.commons.io.FileUtils;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.session.SessionState;

import com.linkedin.coral.common.HiveMetastoreClient;
import com.linkedin.coral.common.HiveMscAdapter;


public class TestUtils {

  public static final String CORAL_MV_TEST_DIR = "coral.mv.test.dir";

  static void run(Driver driver, String sql) {
    while (true) {
      try {
        driver.run(sql);
      } catch (CommandNeedRetryException e) {
        continue;
      }
      break;
    }
  }

  public static HiveMetastoreClient setupTestMetastore(HiveConf conf) throws HiveException, MetaException, IOException {
    String testDir = conf.get(CORAL_MV_TEST_DIR);
    System.out.println("Test Workspace: " + testDir);

    // Clean up any existing test directory
    File testDirFile = new File(testDir);
    if (testDirFile.exists()) {
      System.out.println("Cleaning up existing test directory...");
      try {
        FileUtils.deleteDirectory(testDirFile);
        // Wait a bit for filesystem to release locks (especially on Windows/Derby)
        Thread.sleep(100);
      } catch (Exception e) {
        System.err.println("Warning: Could not fully clean test directory: " + e.getMessage());
        // Try to continue anyway
      }
    }

    // Ensure parent directories exist
    testDirFile.mkdirs();

    System.out.println("Starting Hive session...");
    SessionState.start(conf);
    Driver driver = new Driver(conf);

    // Create test database and tables for materialized view optimization tests
    System.out.println("Creating test tables...");
    run(driver, "CREATE DATABASE IF NOT EXISTS default");
    run(driver, "USE default");
    run(driver, "CREATE TABLE IF NOT EXISTS default.tableOne(a int, b varchar(30), c double)");
    run(driver, "CREATE TABLE IF NOT EXISTS default.tableTwo(x int, y double)");
    run(driver, "CREATE TABLE IF NOT EXISTS default.tableThree(id int, value varchar(50))");
    System.out.println("Test tables created successfully");

    HiveMetastoreClient mscAdapter = new HiveMscAdapter(Hive.get(conf).getMSC());
    return mscAdapter;
  }

  public static HiveConf loadResourceHiveConf() {
    InputStream hiveConfStream = TestUtils.class.getClassLoader().getResourceAsStream("hive.xml");
    HiveConf hiveConf = new HiveConf();

    // Set test directory with unique name to avoid conflicts
    String testDir = System.getProperty("java.io.tmpdir") + "/coral/mv/" + UUID.randomUUID().toString();
    hiveConf.set(CORAL_MV_TEST_DIR, testDir);

    if (hiveConfStream != null) {
      System.out.println("Loading hive.xml configuration from resources");
      hiveConf.addResource(hiveConfStream);
    } else {
      System.err.println("WARNING: hive.xml not found in classpath, using default configuration");
      // Manually configure essential properties if hive.xml is not found
      hiveConf.set("hive.exec.scratchdir", testDir + "/hive-scratch-dir");
      hiveConf.set("hive.metastore.warehouse.dir", testDir + "/warehouse");
      hiveConf.set("javax.jdo.option.ConnectionDriverName", "org.apache.derby.jdbc.EmbeddedDriver");
      hiveConf.set("javax.jdo.option.ConnectionURL",
          "jdbc:derby:;databaseName=" + testDir + "/metastore.db;create=true");
      hiveConf.set("hive.metastore.schema.verification", "false");
      hiveConf.set("datanucleus.schema.autoCreateTables", "true");
    }

    hiveConf.set("mapreduce.framework.name", "local");
    hiveConf.set("_hive.hdfs.session.path", "/tmp/coral");
    hiveConf.set("_hive.local.session.path", "/tmp/coral");

    System.out.println("HiveConf loaded with test directory: " + testDir);
    return hiveConf;
  }
}
