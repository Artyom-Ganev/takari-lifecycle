package io.takari.maven.plugins.compiler.incremental;

import io.takari.incrementalbuild.spi.DefaultBuildContext;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.*;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.DirectoryScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

public abstract class AbstractCompileMojo extends AbstractMojo {

  // I much prefer slf4j over plexus logger api
  private final Logger log = LoggerFactory.getLogger(getClass());

  public static enum Proc {
    proc, only, none
  }

  /**
   * The -source argument for the Java compiler.
   */
  @Parameter(property = "maven.compiler.source", defaultValue = "1.6")
  private String source;

  /**
   * The -target argument for the Java compiler. The default depends on the value of {@code source}
   * as defined in javac documentation.
   * 
   * @see http://docs.oracle.com/javase/6/docs/technotes/tools/solaris/javac.html
   */
  @Parameter(property = "maven.compiler.target")
  private String target;

  /**
   * Allows running the compiler in a separate process. If <code>false</code> it uses the built in
   * compiler, while if <code>true</code> it will use an executable.
   */
  @Parameter(property = "maven.compiler.fork", defaultValue = "false")
  private boolean fork;

  /**
   * <p>
   * Sets whether annotation processing is performed or not. Only applies to JDK 1.6+ If not set, no
   * annotation processing is performed.
   * </p>
   * <p>
   * Allowed values are:
   * </p>
   * <ul>
   * <li><code>proc</code> - both compilation and annotation processing are performed at the same
   * time.</li>
   * <li><code>none</code> - no annotation processing is performed.</li>
   * <li><code>only</code> - only annotation processing is done, no compilation.</li>
   * </ul>
   * 
   * @since 2.2
   */
  @Parameter(defaultValue = "none")
  private Proc proc = Proc.none;

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

  @Component
  private ProjectClasspathDigester digester;

  private Set<String> changedDependencyTypes;

  public Charset getSourceEncoding() {
    // TODO
    // final Charset sourceCharset = sourceEncoding == null ? null :
    // Charset.forName(sourceEncoding);
    return null;
  }

  public List<File> getSources() {
    List<File> sources = new ArrayList<File>();
    for (String sourceRoot : getSourceRoots()) {
      DirectoryScanner scanner = new DirectoryScanner();
      scanner.setBasedir(sourceRoot);
      // TODO this is a bug in project model, includes/excludes should be per sourceRoot
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
      scanner.scan();
      for (String relpath : scanner.getIncludedFiles()) {
        sources.add(new File(sourceRoot, relpath));
      }
    }
    return sources;
  }

  protected abstract Set<String> getSourceRoots();

  protected abstract Set<String> getIncludes();

  protected abstract Set<String> getExcludes();

  public abstract File getOutputDirectory();

  public abstract List<Artifact> getCompileArtifacts();

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

  public List<String> getCompilerOptions() {
    List<String> options = new ArrayList<String>();

    // output directory
    options.add("-d");
    options.add(getOutputDirectory().getAbsolutePath());

    options.add("-source");
    options.add(getSource());

    if (getTarget() != null) {
      options.add("-target");
      options.add(getTarget());
    }

    options.add("-classpath");
    options.add(getClasspath());

    switch (proc) {
      case only:
        options.add("-proc:only");
        break;
      case proc:
        // this is the javac default
        break;
      case none:
        options.add("-proc:none");
        break;
    }
    if (isAnnotationProcessing()) {
      options.add("-s");
      options.add(getGeneratedSourcesDirectory().getAbsolutePath());
    }

    return options;
  }

  public boolean isAnnotationProcessing() {
    return proc != Proc.none;
  }

  public String getClasspath() {
    StringBuilder cp = new StringBuilder();
    cp.append(getOutputDirectory().getAbsolutePath());
    for (Artifact cpe : getCompileArtifacts()) {
      File file = cpe.getFile();
      if (file != null) {
        if (cp.length() > 0) {
          cp.append(File.pathSeparatorChar);
        }
        cp.append(file.getAbsolutePath());
      }
    }
    return cp.toString();
  }

  /**
   * Returns set of dependency types that changed structurally compared to the previous build,
   * including new and deleted dependency types.
   */
  public Set<String> getChangedDependencyTypes() {
    return changedDependencyTypes;
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    Stopwatch stopwatch = new Stopwatch().start();

    mkdirs(getOutputDirectory());
    if (isAnnotationProcessing()) {
      mkdirs(getGeneratedSourcesDirectory());
    }

    try {
      this.changedDependencyTypes = digester.digestDependencies(getCompileArtifacts());

      final List<File> sources = getSources();

      log.info("Compiling {} sources to {}", sources.size(), getOutputDirectory());

      if (!fork) {
        new CompilerJavac(context, this).compile(sources);
      } else {
        CompilerJavacLauncher compiler = new CompilerJavacLauncher(context, this);
        compiler.setBasedir(basedir);
        compiler.setJar(artifact.getFile());
        compiler.setBuildDirectory(buildDirectory);
        compiler.compile(sources);
      }

      digester.writeTypeIndex(getOutputDirectory());

      log.info("Compiled {} sources in {} ms", sources.size(),
          stopwatch.elapsed(TimeUnit.MILLISECONDS));
    } catch (IOException e) {
      throw new MojoExecutionException("Could not compile project", e);
    }
  }

  protected File mkdirs(File dir) throws MojoExecutionException {
    if (!dir.isDirectory() && !dir.mkdirs()) {
      throw new MojoExecutionException("Could not create directory " + dir);
    }
    return dir;
  }
}
