package io.takari.maven.plugins.compile;

import static org.apache.maven.plugin.testing.resources.TestResources.cp;

import java.io.File;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.Assert;
import org.junit.Test;

public class CompileIncrementalTest extends AbstractCompileTest {

  public CompileIncrementalTest(String compilerId) {
    super(compilerId);
  }

  @Test
  public void testBasic() throws Exception {
    ClasspathEntryDigester digester = new ClasspathEntryDigester();

    File basedir = compile("compile-incremental/basic");
    File classes = new File(basedir, "target/classes");
    mojos.assertBuildOutputs(classes, "basic/Basic.class");
    ClasspathEntryIndex index = digester.readIndex(classes, 0);
    Assert.assertTrue(index.isPersistent());
    Assert.assertNotNull(index.getIndex().get("basic.Basic"));

    // no-change rebuild
    compile(basedir);
    mojos.assertBuildOutputs(classes, new String[0]);
    mojos.assertDeletedOutputs(classes, new String[0]);
    mojos.assertCarriedOverOutputs(classes, "basic/Basic.class");
    index = digester.readIndex(classes, 0);
    Assert.assertTrue(index.isPersistent());
    Assert.assertNotNull(index.getIndex().get("basic.Basic"));

    // change
    cp(basedir, "src/main/java/basic/Basic.java-modified", "src/main/java/basic/Basic.java");
    compile(basedir);
    mojos.assertBuildOutputs(classes, "basic/Basic.class");
    index = digester.readIndex(classes, 0);
    Assert.assertTrue(index.isPersistent());
    Assert.assertNotNull(index.getIndex().get("basic.Basic"));
  }

  @Test
  public void testDelete() throws Exception {
    File basedir = compile("compile-incremental/delete");
    mojos.assertBuildOutputs(new File(basedir, "target/classes"), "delete/Delete.class",
        "delete/Keep.class");

    Assert.assertTrue(new File(basedir, "src/main/java/delete/Delete.java").delete());
    compile(basedir);
    if ("jdt".equals(compilerId)) {
      mojos.assertCarriedOverOutputs(new File(basedir, "target/classes"), "delete/Keep.class");
    } else {
      mojos.assertBuildOutputs(new File(basedir, "target/classes"), "delete/Keep.class");
    }
    mojos.assertDeletedOutputs(new File(basedir, "target/classes"), "delete/Delete.class");
  }

  @Test
  public void testError() throws Exception {
    ErrorMessage expected = new ErrorMessage(compilerId);
    expected.setSnippets("jdt", "ERROR Error.java [4:11] Errorr cannot be resolved to a type");
    expected.setSnippets("javac", "ERROR Error.java [4:11]", "cannot find symbol", "class Errorr",
        "location", "class error.Error");

    File basedir = resources.getBasedir("compile-incremental/error");
    try {
      compile(basedir);
      Assert.fail();
    } catch (MojoExecutionException e) {
      Assert.assertEquals("1 error(s) encountered, see previous message(s) for details",
          e.getMessage());
    }
    mojos.assertBuildOutputs(basedir, new String[0]);
    mojos.assertMessage(basedir, "src/main/java/error/Error.java", expected);

    // no change rebuild, should still fail with the same error
    try {
      compile(basedir);
      Assert.fail();
    } catch (MojoExecutionException e) {
      Assert.assertEquals("1 error(s) encountered, see previous message(s) for details",
          e.getMessage());
    }
    mojos.assertBuildOutputs(basedir, new String[0]);
    mojos.assertMessage(basedir, "src/main/java/error/Error.java", expected);

    // fixed the error should clear the message during next build
    cp(basedir, "src/main/java/error/Error.java-fixed", "src/main/java/error/Error.java");
    compile(basedir);
    mojos.assertBuildOutputs(basedir, "target/classes/error/Error.class");
    mojos.assertMessages(basedir, "target/classes/error/Error.class", new String[0]);
  }

  @Test
  public void testClasspath_reactor() throws Exception {
    File basedir = resources.getBasedir("compile-incremental/classpath");
    File moduleB = new File(basedir, "module-b");
    File moduleA = new File(basedir, "module-a");

    compile(moduleB);
    MavenProject projectA = mojos.readMavenProject(moduleA);
    addDependency(projectA, "module-b", new File(moduleB, "target/classes"));
    mojos.compile(projectA);
    mojos.assertBuildOutputs(moduleA, "target/classes/modulea/ModuleA.class");

    // no change rebuild
    mojos.compile(projectA);
    mojos.assertBuildOutputs(moduleA, new String[0]);

    // dependency changed "non-structurally"
    cp(moduleB, "src/main/java/moduleb/ModuleB.java-comment", "src/main/java/moduleb/ModuleB.java");
    compile(moduleB);
    mojos.compile(projectA);
    mojos.assertBuildOutputs(moduleA, new String[0]);

    // dependency changed "structurally"
    cp(moduleB, "src/main/java/moduleb/ModuleB.java-method", "src/main/java/moduleb/ModuleB.java");
    compile(moduleB);
    mojos.compile(projectA);
    mojos.assertBuildOutputs(moduleA, "target/classes/modulea/ModuleA.class");
  }

  @Test
  public void testClasspath_dependency() throws Exception {
    File basedir = resources.getBasedir("compile-incremental/classpath");
    File moduleB = new File(basedir, "module-b");
    File moduleA = new File(basedir, "module-a");

    MavenProject projectA = mojos.readMavenProject(moduleA);
    addDependency(projectA, "module-b", new File(moduleB, "module-b.jar"));
    mojos.compile(projectA);
    mojos.assertBuildOutputs(moduleA, "target/classes/modulea/ModuleA.class");

    // no change rebuild
    projectA = mojos.readMavenProject(moduleA);
    addDependency(projectA, "module-b", new File(moduleB, "module-b.jar"));
    mojos.compile(projectA);
    mojos.assertBuildOutputs(moduleA, new String[0]);

    // dependency changed "non-structurally"
    projectA = mojos.readMavenProject(moduleA);
    addDependency(projectA, "module-b", new File(moduleB, "module-b-comment.jar"));
    mojos.compile(projectA);
    mojos.assertBuildOutputs(moduleA, new String[0]);

    // dependency changed "structurally"
    projectA = mojos.readMavenProject(moduleA);
    addDependency(projectA, "module-b", new File(moduleB, "module-b-method.jar"));
    mojos.compile(projectA);
    mojos.assertBuildOutputs(moduleA, "target/classes/modulea/ModuleA.class");
  }
}
