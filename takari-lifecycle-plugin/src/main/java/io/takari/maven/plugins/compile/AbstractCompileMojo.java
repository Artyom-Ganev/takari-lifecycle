package io.takari.maven.plugins.compile;

import io.takari.incrementalbuild.Incremental;
import io.takari.incrementalbuild.Incremental.Configuration;
import io.takari.incrementalbuild.spi.DefaultBuildContext;
import io.takari.maven.plugins.compile.javac.CompilerJavac;
import io.takari.maven.plugins.compile.javac.CompilerJavacLauncher;
import io.takari.maven.plugins.compile.jdt.CompilerJdt;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
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
   * The -encoding argument for the Java compiler.
   *
   * @since 2.1
   */
  @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}")
  private String encoding;

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
   * The compiler id of the compiler to use, one of {@code javac}, {@code forked-javac} or
   * {@code jdt}.
   */
  @Parameter(property = "maven.compiler.compilerId", defaultValue = "javac")
  private String compilerId;

  /**
   * Initial size, in megabytes, of the memory allocation pool, ex. "64", "64m" if {@link #fork} is
   * set to <code>true</code>.
   *
   * @since 2.0.1
   */
  @Parameter(property = "maven.compiler.meminitial")
  private String meminitial;

  /**
   * Sets the maximum size, in megabytes, of the memory allocation pool, ex. "128", "128m" if
   * {@link #fork} is set to <code>true</code>.
   *
   * @since 2.0.1
   */
  @Parameter(property = "maven.compiler.maxmem")
  private String maxmem;

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

  /**
   * <p>
   * Names of annotation processors to run. Only applies to JDK 1.6+ If not set, the default
   * annotation processors discovery process applies.
   * </p>
   *
   * @since 2.2
   */
  @Parameter
  private String[] annotationProcessors;

  /**
   * Set to <code>true</code> to show messages about what the compiler is doing.
   */
  @Parameter(property = "maven.compiler.verbose", defaultValue = "false")
  private boolean verbose;

  //

  @Parameter(defaultValue = "${project.file}", readonly = true)
  @Incremental(configuration = Configuration.ignore)
  private File pom;

  @Parameter(defaultValue = "${project.basedir}", readonly = true)
  @Incremental(configuration = Configuration.ignore)
  private File basedir;

  @Parameter(defaultValue = "${project.build.directory}", readonly = true)
  @Incremental(configuration = Configuration.ignore)
  private File buildDirectory;

  @Parameter(defaultValue = "${plugin.pluginArtifact}", readonly = true)
  @Incremental(configuration = Configuration.ignore)
  private Artifact pluginArtifact;

  @Parameter(defaultValue = "${project.artifact}", readonly = true)
  @Incremental(configuration = Configuration.ignore)
  private Artifact artifact;

  @Component
  private DefaultBuildContext<?> context;

  @Component
  private ProjectClasspathDigester digester;

  public Charset getSourceEncoding() {
    return encoding == null ? null : Charset.forName(encoding);
  }

  public List<File> getSources() {
    List<File> sources = new ArrayList<File>();
    StringBuilder msg = new StringBuilder();
    for (String sourcePath : getSourceRoots()) {
      File sourceRoot = new File(sourcePath);
      msg.append("\n").append(sourcePath);
      if (!sourceRoot.isDirectory()) {
        msg.append("\n   does not exist or not a directory, skiped");
        continue;
      }
      DirectoryScanner scanner = new DirectoryScanner();
      scanner.setBasedir(sourceRoot);
      // TODO this is a bug in project model, includes/excludes should be per sourceRoot
      Set<String> includes = getIncludes();
      if (includes == null || includes.isEmpty()) {
        includes = Collections.singleton("**/*.java");
      }
      scanner.setIncludes(includes.toArray(new String[includes.size()]));
      Set<String> excludes = getExcludes();
      if (excludes != null && !excludes.isEmpty()) {
        scanner.setExcludes(excludes.toArray(new String[excludes.size()]));
      }
      scanner.scan();
      String[] includedFiles = scanner.getIncludedFiles();
      for (String relpath : includedFiles) {
        sources.add(new File(sourceRoot, relpath));
      }
      if (log.isDebugEnabled()) {
        msg.append("\n   includes=").append(includes.toString());
        msg.append(" excludes=").append(excludes != null ? excludes.toString() : "[]");
        msg.append(" matched=").append(includedFiles.length);
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("Compile source roots:{}", msg);
    }
    return sources;
  }

  public abstract Set<String> getSourceRoots();

  public abstract Set<String> getIncludes();

  public abstract Set<String> getExcludes();

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

  public Proc getProc() {
    return proc;
  }

  public boolean isAnnotationProcessing() {
    return proc != Proc.none;
  }

  public String[] getAnnotationProcessors() {
    return annotationProcessors;
  }

  public boolean isVerbose() {
    return verbose;
  }

  public String getMaxmem() {
    return maxmem;
  }

  public String getMeminitial() {
    return meminitial;
  }

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    Stopwatch stopwatch = new Stopwatch().start();

    final AbstractCompiler compiler;
    if ("javac".equals(compilerId)) {
      compiler = new CompilerJavac(context, this, digester);
    } else if ("forked-javac".equals(compilerId)) {
      CompilerJavacLauncher javacLauncher = new CompilerJavacLauncher(context, this, digester);
      javacLauncher.setBasedir(basedir);
      javacLauncher.setJar(pluginArtifact.getFile());
      javacLauncher.setBuildDirectory(buildDirectory);
      compiler = javacLauncher;
    } else if ("jdt".equals(compilerId)) {
      compiler = new CompilerJdt(this, context);
    } else {
      throw new MojoExecutionException("Unsupported compilerId" + compilerId);
    }

    final List<File> sources = getSources();
    if (sources.isEmpty()) {
      log.info("No sources, skipping compilation");
      return;
    }

    mkdirs(getOutputDirectory());
    if (isAnnotationProcessing()) {
      mkdirs(getGeneratedSourcesDirectory());
    }

    try {
      List<Artifact> classpath = getCompileArtifacts();
      if (log.isDebugEnabled()) {
        StringBuilder msg = new StringBuilder();
        for (Artifact artifact : classpath) {
          msg.append("\n   ").append(artifact.getFile());
        }
        log.debug("Compile classpath: {} entries{}", classpath.size(), msg.toString());
      }
      boolean classpathChanged = compiler.setClasspath(classpath);
      boolean sourcesChanged = compiler.setSources(sources);

      if (!sourcesChanged && !classpathChanged) {
        compiler.skipCompilation();
        log.info("Skipped compilation, all {} sources are up to date", sources.size());
        return;
      }

      log.info("Compiling {} sources to {}", sources.size(), getOutputDirectory());

      compiler.compile();

      artifact.setFile(getOutputDirectory());

      // TODO report actual number of sources compiled
      log.info("Compiled {} sources ({} ms)", sources.size(),
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
