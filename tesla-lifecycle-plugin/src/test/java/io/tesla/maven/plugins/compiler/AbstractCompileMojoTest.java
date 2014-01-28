package io.tesla.maven.plugins.compiler;

import io.takari.incrementalbuild.maven.testing.BuildAvoidanceRule;

import java.io.File;

import org.apache.maven.plugin.testing.resources.TestResources;
import org.junit.Assert;
import org.junit.Rule;

public abstract class AbstractCompileMojoTest {

  @Rule
  public final TestResources resources = new TestResources();

  @Rule
  public final BuildAvoidanceRule mojos = new BuildAvoidanceRule();

  protected void compile(File basedir) throws Exception {
    mojos.executeMojo(basedir, "compile");
  }

  protected void testCompile(File basedir) throws Exception {
    mojos.executeMojo(basedir, "testCompile");
  }

  protected File getCompiledBasedir(String location) throws Exception {
    final File basedir = resources.getBasedir(location);
    compile(basedir);
    return basedir;
  }

  /**
   * Asserts specified output exists and is not older than specified input
   */
  protected static void assertBuildOutput(File basedir, String input, String output) {
    File inputFile = new File(basedir, input);
    File outputFile = new File(basedir, output);
    Assert.assertTrue("output is older than input",
        outputFile.lastModified() >= inputFile.lastModified());
  }

}
