package io.takari.maven.plugins.jar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import io.takari.hash.FingerprintSha1Streaming;
import io.takari.incrementalbuild.maven.testing.IncrementalBuildRule;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Properties;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.apache.maven.plugin.testing.resources.TestResources;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import com.google.common.io.Files;

public class JarTest {

  @Rule
  public final TestResources resources = new TestResources();

  @Rule
  public final IncrementalBuildRule mojos = new IncrementalBuildRule();

  @Test
  public void jarCreation() throws Exception {
    //
    // Generate some resources to JAR
    //
    File basedir = resources.getBasedir("jar/project-with-resources");
    mojos.executeMojo(basedir, "process-resources");
    File resource = new File(basedir, "target/classes/resource.txt");
    assertTrue(resource.exists());
    String line = Files.readFirstLine(resource, Charset.defaultCharset());
    assertTrue(line.contains("resource.txt"));
    //
    // Generate the JAR a first time and capture the fingerprint
    //
    mojos.executeMojo(basedir, "jar");
    File jar0 = new File(basedir, "target/test-1.0.jar");
    assertTrue(jar0.exists());
    String fingerprint0 = new FingerprintSha1Streaming().fingerprint(jar0);
    //
    // Generate the JAR a second time and ensure that the fingerprint is still the same when
    // the JAR content is the same. The outer SHA1 of a JAR built at two points in time will
    // be different even though the content has not changed.
    //
    mojos.executeMojo(basedir, "jar");
    File jar1 = new File(basedir, "target/test-1.0.jar");
    Assert.assertTrue(jar1.exists());
    String fingerprint1 = new FingerprintSha1Streaming().fingerprint(jar1);
    assertEquals("We expect the JAR to have the same fingerprint after repeated builds.",
        fingerprint0, fingerprint1);

    // Make sure our maven properties file is written correctly
    ZipFile zip0 = new ZipFile(jar1);
    try {
      String pomProperties = "META-INF/io.takari.lifecycle.its/test/pom.properties";
      ZipEntry entry = zip0.getEntry(pomProperties);
      if (entry != null) {
        InputStream is = zip0.getInputStream(entry);
        Properties p = new Properties();
        p.load(is);
        assertEquals("io.takari.lifecycle.its", p.getProperty("groupId"));
        assertEquals("test", p.getProperty("artifactId"));
        assertEquals("1.0", p.getProperty("version"));
      } else {
        fail("We expected the standard pom.properties: " + pomProperties);
      }
    } finally {
      zip0.close();
    }

    ZipFile zip1 = new ZipFile(jar1);
    String manifestEntryName = "META-INF/MANIFEST.MF";
    ZipEntry manifestEntry = zip1.getEntry(manifestEntryName);
    if (manifestEntry != null) {
      InputStream is = zip1.getInputStream(manifestEntry);
      Manifest p = new Manifest(is);
      assertNotNull(p.getMainAttributes().getValue("Built-By"));
      assertNotNull(p.getMainAttributes().getValue("Build-Jdk"));
      assertEquals("1.0", p.getMainAttributes().getValue("Manifest-Version"));
      assertEquals("test", p.getMainAttributes().getValue("Implementation-Title"));
      assertEquals("1.0", p.getMainAttributes().getValue("Implementation-Version"));
      assertEquals("io.takari.lifecycle.its",
          p.getMainAttributes().getValue("Implementation-Vendor-Id"));
    } else {
      fail("We expected the standard META-INF/MANIFEST.MF");
    }
  }
}
