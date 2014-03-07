package io.takari.maven.plugins.compiler.incremental;

import io.takari.incrementalbuild.spi.DefaultBuildContext;
import io.takari.incrementalbuild.util.DirectoryScannerAdapter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.DirectoryScanner;

import com.google.common.collect.Iterables;

public abstract class AbstractCompileMojo extends AbstractMojo {

  /**
   * The -source argument for the Java compiler.
   */
  @Parameter(property = "maven.compiler.source", defaultValue = "1.6")
  private String source;

  /**
   * The -target argument for the Java compiler.
   */
  @Parameter(property = "maven.compiler.target", defaultValue = "1.6")
  private String target;

  /**
   * Allows running the compiler in a separate process. If <code>false</code> it uses the built in
   * compiler, while if <code>true</code> it will use an executable.
   */
  @Parameter(property = "maven.compiler.fork", defaultValue = "false")
  private boolean fork;

  /**
   * <p>
   * Sets whether annotation processing is performed or not. Only applies to JDK 1.6+ If not set,
   * both compilation and annotation processing are performed at the same time.
   * </p>
   * <p>
   * Allowed values are:
   * </p>
   * <ul>
   * <li><code>none</code> - no annotation processing is performed.</li>
   * <li><code>only</code> - only annotation processing is done, no compilation.</li>
   * </ul>
   *
   * @since 2.2
   */
  @Parameter
  private String proc;

  //

  @Parameter(defaultValue = "${project.file}", readonly = true)
  private File pom;

  @Parameter(defaultValue = "${project.basedir}", readonly = true)
  private File basedir;

  @Parameter(defaultValue = "${project.build.directory}", readonly = true)
  private File buildDirectory;

  @Parameter(defaultValue = "${plugin.pluginArtifact}", readonly = true)
  private Artifact artifact;

  @Component
  private DefaultBuildContext<?> context;

  public Charset getSourceEncoding() {
    // TODO
    // final Charset sourceCharset = sourceEncoding == null ? null :
    // Charset.forName(sourceEncoding);
    return null;
  }

  public Iterable<File> getSources() {
    List<Iterable<File>> sources = new ArrayList<Iterable<File>>();
    for (String sourceRoot : getSourceRoots()) {
      DirectoryScanner scanner = new DirectoryScanner();
      scanner.setBasedir(sourceRoot);
      // TODO this is an API bug, includes/excludes should be per sourceRoot
      Set<String> includes = getIncludes();
      if (includes != null && !includes.isEmpty()) {
        scanner.setIncludes(includes.toArray(new String[includes.size()]));
      } else {
        scanner.setIncludes(new String[] {"**/*.java"});
      }
      Set<String> excludes = getExcludes();
      if (excludes != null && !excludes.isEmpty()) {
        scanner.setExcludes(excludes.toArray(new String[excludes.size()]));
      }
      sources.add(new DirectoryScannerAdapter(scanner));
    }
    return Iterables.concat(sources);
  }

  protected abstract Set<String> getSourceRoots();

  protected abstract Set<String> getIncludes();

  protected abstract Set<String> getExcludes();

  public abstract File getOutputDirectory();

  public abstract List<String> getClasspathElements();

  public abstract File getGeneratedSourcesDirectory();

  public final File getPom() {
    return pom;
  }

  public String getTarget() {
    return target;
  }

  public String getSource() {
    return source;
  }

  public Iterable<String> getCompilerOptions() {
    List<String> options = new ArrayList<String>();

    // output directory
    options.add("-d");
    options.add(getOutputDirectory().getAbsolutePath());

    options.add("-target");
    options.add(getTarget());

    options.add("-source");
    options.add(getSource());

    options.add("-classpath");
    options.add(getClasspath());

    if (proc != null) {
      options.add("-proc:" + proc);
    }
    if (isAnnotationProcessing()) {
      options.add("-s");
      options.add(getGeneratedSourcesDirectory().getAbsolutePath());
    }

    return options;
  }

  public boolean isAnnotationProcessing() {
    return !"none".equals(proc);
  }

  public String getClasspath() {
    StringBuilder cp = new StringBuilder();
    for (String cpe : getClasspathElements()) {
      if (cp.length() > 0) {
        cp.append(File.pathSeparatorChar);
      }
      cp.append(cpe);
    }
    return cp.toString();
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    mkdirs(getOutputDirectory());
    if (isAnnotationProcessing()) {
      mkdirs(getGeneratedSourcesDirectory());
    }

    if (!fork) {
      new CompilerJavac(context, this).compile();
    } else {
      CompilerJavacLauncher compiler = new CompilerJavacLauncher(context, this);
      compiler.setBasedir(basedir);
      compiler.setJar(artifact.getFile());
      compiler.setBuildDirectory(buildDirectory);
      try {
        compiler.compile();
      } catch (IOException e) {
        throw new MojoExecutionException("Could not execute compiler", e);
      }
    }
  }

  protected File mkdirs(File dir) throws MojoExecutionException {
    if (!dir.isDirectory() && !dir.mkdirs()) {
      throw new MojoExecutionException("Could not create directory " + dir);
    }
    return dir;
  }
}
