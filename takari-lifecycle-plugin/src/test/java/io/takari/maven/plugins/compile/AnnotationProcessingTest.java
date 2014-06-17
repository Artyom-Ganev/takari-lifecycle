package io.takari.maven.plugins.compile;

import static org.apache.maven.plugin.testing.resources.TestResources.cp;
import io.takari.maven.plugins.compile.AbstractCompileMojo.Proc;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runners.Parameterized.Parameters;

public class AnnotationProcessingTest extends AbstractCompileTest {

  public AnnotationProcessingTest(String compilerId) {
    super(compilerId);
  }

  @Parameters(name = "{0}")
  public static Iterable<Object[]> compilers() {
    List<Object[]> compilers = new ArrayList<Object[]>();
    if (isJava7) {
      // in-process annotation processing is not supported on java 6
      compilers.add(new Object[] {"javac"});
    }
    compilers.add(new Object[] {"forked-javac"});
    // compilers.add(new Object[] {"jdt"});
    return compilers;
  }

  private File procCompile(String projectName, Proc proc, Xpp3Dom... parameters) throws Exception, IOException {
    File processor = compileAnnotationProcessor();

    File basedir = resources.getBasedir(projectName);
    return processAnnotations(basedir, proc, processor, parameters);
  }

  private File processAnnotations(File basedir, Proc proc, File processor, Xpp3Dom... parameters) throws Exception {
    MavenProject project = mojos.readMavenProject(basedir);
    processAnnotations(project, processor, proc, parameters);
    return basedir;
  }

  protected void processAnnotations(MavenProject project, File processor, Proc proc, Xpp3Dom... parameters) throws Exception {
    MavenSession session = mojos.newMavenSession(project);
    MojoExecution execution = mojos.newMojoExecution();

    addDependency(project, "processor", new File(processor, "target/classes"));

    Xpp3Dom configuration = execution.getConfiguration();

    if (proc != null) {
      configuration.addChild(newParameter("proc", proc.name()));
    }
    if (parameters != null) {
      for (Xpp3Dom parameter : parameters) {
        configuration.addChild(parameter);
      }
    }

    mojos.executeMojo(session, project, execution);
  }

  private File compileAnnotationProcessor() throws Exception, IOException {
    File processor = compile("compile-proc/processor");
    cp(processor, "src/main/resources/META-INF/services/javax.annotation.processing.Processor", "target/classes/META-INF/services/javax.annotation.processing.Processor");
    return processor;
  }


  @Test
  public void testProc_only() throws Exception {
    File basedir = procCompile("compile-proc/proc", Proc.only);
    mojos.assertBuildOutputs(new File(basedir, "target/generated-sources/annotations"), "proc/GeneratedSource.java", "proc/AnotherGeneratedSource.java");
  }

  @Test
  public void testProc_default() throws Exception {
    File basedir = procCompile("compile-proc/proc", null);
    mojos.assertBuildOutputs(new File(basedir, "target"), "classes/proc/Source.class");
  }

  @Test
  public void testProc_none() throws Exception {
    File basedir = procCompile("compile-proc/proc", Proc.none);
    mojos.assertBuildOutputs(new File(basedir, "target"), "classes/proc/Source.class");
  }

  @Test
  public void testProc_proc() throws Exception {
    File basedir = procCompile("compile-proc/proc", Proc.proc);
    mojos.assertBuildOutputs(new File(basedir, "target"), //
        "classes/proc/Source.class", //
        "generated-sources/annotations/proc/GeneratedSource.java", //
        "classes/proc/GeneratedSource.class", //
        "generated-sources/annotations/proc/AnotherGeneratedSource.java", //
        "classes/proc/AnotherGeneratedSource.class");
  }

  @Test
  public void testProcTypeReference() throws Exception {
    File basedir = procCompile("compile-proc/proc-type-reference", Proc.proc);
    mojos.assertBuildOutputs(new File(basedir, "target"), //
        "classes/proc/Source.class", //
        "classes/proc/GeneratedSourceSubclass.class", //
        "generated-sources/annotations/proc/GeneratedSource.java", //
        "classes/proc/GeneratedSource.class", //
        "generated-sources/annotations/proc/AnotherGeneratedSource.java", //
        "classes/proc/AnotherGeneratedSource.class");
  }

  @Test
  public void testProc_annotationProcessors() throws Exception {
    Xpp3Dom processors = new Xpp3Dom("annotationProcessors");
    processors.addChild(newParameter("processor", "processor.Processor"));
    File basedir = procCompile("compile-proc/proc", Proc.proc, processors);
    mojos.assertBuildOutputs(new File(basedir, "target"), //
        "classes/proc/Source.class", //
        "generated-sources/annotations/proc/GeneratedSource.java", //
        "classes/proc/GeneratedSource.class");
  }

  @Test
  public void testProc_messages() throws Exception {
    ErrorMessage expected = new ErrorMessage(compilerId);
    expected.setSnippets("javac", "ERROR BrokenSource.java [2:29]", "cannot find symbol");

    File processor = compileAnnotationProcessor();
    File basedir = resources.getBasedir("compile-proc/proc");

    Xpp3Dom processors = new Xpp3Dom("annotationProcessors");
    processors.addChild(newParameter("processor", "processor.BrokenProcessor"));
    try {
      processAnnotations(basedir, Proc.proc, processor, processors);
      Assert.fail();
    } catch (MojoExecutionException e) {
      // expected
    }
    mojos.assertBuildOutputs(new File(basedir, "target"), //
        "generated-sources/annotations/proc/BrokenSource.java");
    assertProcMessage(basedir, "target/generated-sources/annotations/proc/BrokenSource.java", expected);

    // no change rebuild should produce the same messages
    try {
      processAnnotations(basedir, Proc.proc, processor, processors);
      Assert.fail();
    } catch (MojoExecutionException e) {
      // expected
    }
    mojos.assertCarriedOverOutputs(new File(basedir, "target"), //
        "generated-sources/annotations/proc/BrokenSource.java");
    assertProcMessage(basedir, "target/generated-sources/annotations/proc/BrokenSource.java", expected);
  }

  private void assertProcMessage(File basedir, String path, ErrorMessage expected) throws Exception {
    // javac reports the same compilation error twice when Proc.proc
    Set<String> messages = new HashSet<String>(mojos.getBuildContextLog().getMessages(new File(basedir, path)));
    Assert.assertEquals(messages.toString(), 1, messages.size());
    String message = messages.iterator().next();
    Assert.assertTrue(expected.isMatch(message));
  }

  @Test
  public void testProcessorOptions() throws Exception {
    Xpp3Dom processors = new Xpp3Dom("annotationProcessors");
    processors.addChild(newParameter("processor", "processor.ProcessorWithOptions"));

    Xpp3Dom options = new Xpp3Dom("annotationProcessorOptions");
    options.addChild(newParameter("optionA", "valueA"));
    options.addChild(newParameter("optionB", "valueB"));
    procCompile("compile-proc/proc", Proc.proc, processors, options);
  }

  @Test
  public void testStaleGeneratedSourcesCleanup() throws Exception {
    File processor = compileAnnotationProcessor();
    File basedir = resources.getBasedir("compile-proc/proc");

    processAnnotations(basedir, Proc.proc, processor);
    mojos.assertBuildOutputs(new File(basedir, "target"), //
        "classes/proc/Source.class", //
        "generated-sources/annotations/proc/GeneratedSource.java", //
        "classes/proc/GeneratedSource.class", //
        "generated-sources/annotations/proc/AnotherGeneratedSource.java", //
        "classes/proc/AnotherGeneratedSource.class");

    // remove annotation
    cp(basedir, "src/main/java/proc/Source.java-remove-annotation", "src/main/java/proc/Source.java");
    processAnnotations(basedir, Proc.proc, processor);
    mojos.assertDeletedOutputs(new File(basedir, "target"), //
        "generated-sources/annotations/proc/GeneratedSource.java", //
        "classes/proc/GeneratedSource.class", //
        "generated-sources/annotations/proc/AnotherGeneratedSource.java", //
        "classes/proc/AnotherGeneratedSource.class");
  }

  @Test
  @Ignore("not supported with javac, see test comment")
  public void testConvertGeneratedSourceToHandwritten() throws Exception {
    // this test demonstrates the following scenario not currently supported by javac compiler
    // 1. annotation processor generates java source and the generated source is compiled by javac
    // 2. annotation is removed from original source and the generated source is moved to a dependency
    // because javac does not provide originalSource->generatedSource association, it is not possible
    // to eagerly cleanup generatedSource.java and generatedSource.class, which means old version
    // of the generatedSource.class will be used during compilation

    File processor = compileAnnotationProcessor();
    File basedir = resources.getBasedir("compile-proc/proc-incremental-move");
    File moduleA = new File(basedir, "module-a");
    File moduleB = new File(basedir, "module-b");

    Xpp3Dom processors = new Xpp3Dom("annotationProcessors");
    processors.addChild(newParameter("processor", "processor.Processor"));

    mojos.compile(moduleB);
    MavenProject projectA = mojos.readMavenProject(moduleA);
    addDependency(projectA, "module-b", new File(moduleB, "target/classes"));
    processAnnotations(projectA, processor, Proc.proc, processors);
    mojos.assertBuildOutputs(new File(moduleA, "target"), //
        "classes/proc/Source.class", //
        "generated-sources/annotations/proc/GeneratedSource.java", //
        "classes/proc/GeneratedSource.class");

    // move generated source to module-b/src/main/java
    cp(moduleB, "src/main/java/proc/GeneratedSource.java-moved", "src/main/java/proc/GeneratedSource.java");
    cp(moduleA, "src/main/java/modulea/ModuleA.java-new", "src/main/java/modulea/ModuleA.java");
    cp(moduleA, "src/main/java/proc/Source.java-remove-annotation", "src/main/java/proc/Source.java");
    mojos.compile(moduleB);
    mojos.assertBuildOutputs(moduleB, "target/classes/proc/GeneratedSource.class");
    processAnnotations(projectA, processor, Proc.proc, processors);
    mojos.assertBuildOutputs(new File(moduleA, "target"), //
        "classes/proc/Source.class");
    mojos.assertDeletedOutputs(new File(moduleA, "target"), //
        "generated-sources/annotations/proc/GeneratedSource.java", //
        "classes/proc/GeneratedSource.class");
  }
}
