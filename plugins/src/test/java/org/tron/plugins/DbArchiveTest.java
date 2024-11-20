package org.tron.plugins;

import static org.iq80.leveldb.impl.Iq80DBFactory.factory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import picocli.CommandLine;

@Slf4j
public class DbArchiveTest {

  private static final String OUTPUT_DIRECTORY = "output-directory/database/dbArchive";

  private static final String ENGINE = "ENGINE";
  private static final String LEVELDB = "LEVELDB";
  private static final String ROCKSDB = "ROCKSDB";
  private static final String ACCOUNT = "account";
  private static final String ACCOUNT_ROCKSDB = "account-rocksdb";
  private static final String MARKET = "market_pair_price_to_order";
  private static final String ENGINE_FILE = "engine.properties";

  @BeforeClass
  public static void init() throws IOException {
    File file = new File(OUTPUT_DIRECTORY,ACCOUNT);
    factory.open(file,ArchiveManifest.newDefaultLevelDbOptions()).close();
    writeProperty(file.toString() + File.separator + ENGINE_FILE,ENGINE,LEVELDB);

    file = new File(OUTPUT_DIRECTORY,MARKET);
    factory.open(file,ArchiveManifest.newDefaultLevelDbOptions()).close();
    writeProperty(file.toString() + File.separator + ENGINE_FILE,ENGINE,LEVELDB);

    file = new File(OUTPUT_DIRECTORY,ACCOUNT_ROCKSDB);
    factory.open(file,ArchiveManifest.newDefaultLevelDbOptions()).close();
    writeProperty(file.toString() + File.separator + ENGINE_FILE,ENGINE,ROCKSDB);

  }

  @AfterClass
  public static void destroy() {
    deleteDir(new File(OUTPUT_DIRECTORY));
  }

  @Test
  public void testRun() {
    String[] args = new String[] {"db", "archive", "-d", OUTPUT_DIRECTORY };
    CommandLine cli = new CommandLine(new Toolkit());
    Assert.assertEquals(0, cli.execute(args));
  }

  @Test
  public void testHelp() {
    String[] args = new String[] {"db", "archive", "-h"};
    CommandLine cli = new CommandLine(new Toolkit());
    Assert.assertEquals(0, cli.execute(args));
  }

  @Test
  public void testMaxManifest() {
    String[] args = new String[] {"db", "archive", "-d", OUTPUT_DIRECTORY, "-m", "128"};
    CommandLine cli = new CommandLine(new Toolkit());
    Assert.assertEquals(0, cli.execute(args));
  }

  @Test
  public void testNotExist() {
    String[] args = new String[] {"db", "archive", "-d",
        OUTPUT_DIRECTORY + File.separator + UUID.randomUUID()};
    CommandLine cli = new CommandLine(new Toolkit());
    Assert.assertEquals(404, cli.execute(args));
  }

  @Test
  public void testEmpty() {
    File file = new File(OUTPUT_DIRECTORY + File.separator + UUID.randomUUID());
    file.mkdirs();
    file.deleteOnExit();
    String[] args = new String[] {"db", "archive", "-d", file.toString()};
    CommandLine cli = new CommandLine(new Toolkit());
    Assert.assertEquals(0, cli.execute(args));
  }

  private static void writeProperty(String filename, String key, String value) throws IOException {
    File file = new File(filename);
    if (!file.exists()) {
      file.createNewFile();
    }

    try (FileInputStream fis = new FileInputStream(file);
         OutputStream out = new FileOutputStream(file);
         BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(out,
             StandardCharsets.UTF_8))) {
      BufferedReader bf = new BufferedReader(new InputStreamReader(fis, StandardCharsets.UTF_8));
      Properties properties = new Properties();
      properties.load(bf);
      properties.setProperty(key, value);
      properties.store(bw, "Generated by the application.  PLEASE DO NOT EDIT! ");
    } catch (Exception e) {
      logger.warn("{}", e);
    }
  }

  /**
   * delete directory.
   */
  private static boolean deleteDir(File dir) {
    if (dir.isDirectory()) {
      String[] children = dir.list();
      assert children != null;
      for (String child : children) {
        boolean success = deleteDir(new File(dir, child));
        if (!success) {
          logger.warn("can't delete dir:" + dir);
          return false;
        }
      }
    }
    return dir.delete();
  }
}
