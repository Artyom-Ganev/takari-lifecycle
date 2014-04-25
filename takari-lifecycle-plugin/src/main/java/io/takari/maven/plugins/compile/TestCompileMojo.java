package io.takari.maven.plugins.compile;

import io.takari.incrementalbuild.Incremental;
import io.takari.incrementalbuild.Incremental.Configuration;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

@Mojo(name = "testCompile", defaultPhase = LifecyclePhase.TEST_COMPILE, threadSafe = true, requiresDependencyResolution = ResolutionScope.TEST)
public class TestCompileMojo extends AbstractCompileMojo {

  /**
   * The source directories containing the test-source to be compiled.
   */
  @Parameter(defaultValue = "${project.testCompileSourceRoots}", readonly = true, required = true)
  private List<String> compileSourceRoots;

  /**
   * A list of inclusion filters for the compiler.
   */
  @Parameter
  private Set<String> testIncludes = new HashSet<String>();

  /**
   * A list of exclusion filters for the compiler.
   */
  @Parameter
  private Set<String> testExcludes = new HashSet<String>();

  /**
   * Project classpath.
   */
  @Parameter(defaultValue = "${project.testArtifacts}", readonly = true, required = true)
  @Incremental(configuration = Configuration.ignore)
  private List<Artifact> compileArtifacts;

  /**
   * Project main output directory, part of test classpath.
   */
  @Parameter(defaultValue = "${project.build.outputDirectory}", required = true, readonly = true)
  private File mainOutputDirectory;

  /**
   * The directory where compiled test classes go.
   */
  @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true, readonly = true)
  private File outputDirectory;

  /**
   * <p>
   * Specify where to place generated source files created by annotation processing. Only applies to
   * JDK 1.6+
   * </p>
   *
   * @since 2.2
   */
  @Parameter(defaultValue = "${project.build.directory}/generated-test-sources/test-annotations")
  private File generatedTestSourcesDirectory;

  @Override
  public Set<String> getSourceRoots() {
    return new LinkedHashSet<String>(compileSourceRoots);
  }

  @Override
  public Set<String> getIncludes() {
    return testIncludes;
  }

  @Override
  public Set<String> getExcludes() {
    return testExcludes;
  }

  @Override
  public File getOutputDirectory() {
    return outputDirectory;
  }

  @Override
  public List<File> getClasspath() {
    List<File> classpath = new ArrayList<File>();
    classpath.add(mainOutputDirectory);
    for (Artifact artifact : compileArtifacts) {
      File file = artifact.getFile();
      if (file != null) {
        classpath.add(file);
      }
    }
    return classpath;
  }

  @Override
  public File getGeneratedSourcesDirectory() {
    return generatedTestSourcesDirectory;
  }

}
