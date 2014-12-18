/**
 * Copyright (c) 2014 Takari, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package io.takari.maven.plugins.compile;

import io.takari.incrementalbuild.BuildContext;
import io.takari.incrementalbuild.BuildContext.InputMetadata;
import io.takari.incrementalbuild.Incremental;
import io.takari.incrementalbuild.Incremental.Configuration;
import io.takari.incrementalbuild.spi.DefaultBuildContext;
import io.takari.maven.plugins.compile.javac.CompilerJavacLauncher;
import io.takari.maven.plugins.exportpackage.ExportPackageMojo;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.TimeUnit;

import javax.tools.JavaFileObject.Kind;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Stopwatch;

public abstract class AbstractCompileMojo extends AbstractMojo {

  private static final String DEFAULT_COMPILER_LEVEL = "1.7";

  // I much prefer slf4j over plexus logger api
  private final Logger log = LoggerFactory.getLogger(getClass());

  public static enum Proc {
    proc, only, none
  }

  public static enum Debug {
    all, none, source, lines, vars;
  }

  public static enum AccessRulesViolation {
    error, ignore;
  }

  /**
   * The -encoding argument for the Java compiler.
   */
  @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}")
  private String encoding;

  /**
   * The -source argument for the Java compiler.
   */
  @Parameter(property = "maven.compiler.source", defaultValue = DEFAULT_COMPILER_LEVEL)
  private String source;

  /**
   * The -target argument for the Java compiler. The default depends on the value of {@code source} as defined in javac documentation.
   * 
   * @see http://docs.oracle.com/javase/6/docs/technotes/tools/solaris/javac.html
   */
  @Parameter(property = "maven.compiler.target")
  private String target;

  /**
   * The compiler id of the compiler to use, one of {@code javac}, {@code forked-javac} or {@code jdt}.
   */
  @Parameter(property = "maven.compiler.compilerId", defaultValue = "javac")
  private String compilerId;

  /**
   * Initial size, in megabytes, of the memory allocation pool, ex. "64", "64m" if {@link #fork} is set to <code>true</code>.
   */
  @Parameter(property = "maven.compiler.meminitial")
  private String meminitial;

  /**
   * Sets the maximum size, in megabytes, of the memory allocation pool, ex. "128", "128m" if {@link #fork} is set to <code>true</code>.
   */
  @Parameter(property = "maven.compiler.maxmem")
  private String maxmem;

  /**
   * <p>
   * Sets whether annotation processing is performed or not. Only applies to JDK 1.6+ If not set, no annotation processing is performed.
   * </p>
   * <p>
   * Allowed values are:
   * </p>
   * <ul>
   * <li><code>proc</code> - both compilation and annotation processing are performed at the same time.</li>
   * <li><code>none</code> - no annotation processing is performed.</li>
   * <li><code>only</code> - only annotation processing is done, no compilation.</li>
   * </ul>
   */
  @Parameter(defaultValue = "none")
  private Proc proc = Proc.none;

  /**
   * <p>
   * Names of annotation processors to run. Only applies to JDK 1.6+ If not set, the default annotation processors discovery process applies.
   * </p>
   */
  @Parameter
  private String[] annotationProcessors;

  /**
   * Annotation processors options
   */
  @Parameter
  private Map<String, String> annotationProcessorOptions;

  /**
   * Set to <code>true</code> to show messages about what the compiler is doing.
   */
  @Parameter(property = "maven.compiler.verbose", defaultValue = "false")
  private boolean verbose;

  /**
   * Sets whether generated class files include debug information or not.
   * <p>
   * Allowed values
   * <ul>
   * <li><strong>all</strong> or <strong>true</strong> Generate all debugging information, including local variables. This is the default.</li>
   * <li><strong>none</strong> or <strong>false</strong> Do not generate any debugging information.</li>
   * <li>Comma-separated list of
   * <ul>
   * <li><strong>source</strong> Source file debugging information.</li>
   * <li><strong>lines</strong> Line number debugging information.</li>
   * <li><strong>vars</strong> Local variable debugging information.</li>
   * </ul>
   * </li>
   * </ul>
   */
  @Parameter(property = "maven.compiler.debug", defaultValue = "all")
  private String debug;

  /**
   * Set to <code>true</code> to show compilation warnings.
   */
  @Parameter(property = "maven.compiler.showWarnings", defaultValue = "false")
  private boolean showWarnings;

  /**
   * Sets classpath access rules enforcement policy
   * <ul>
   * <li>{@code ignore} (the default): ignore classpath access rules violations</li>
   * <li>{@code error}: treat classpath access rules violations as compilation errors</li>
   * </ul>
   * <p>
   * Classpath access rules:
   * <ul>
   * <li>Forbid references to types from indirect, i.e. transitive, dependencies.</li>
   * <li>Forbid references to types from non-exported packages.</li>
   * </ul>
   *
   * @see ExportPackageMojo
   * @see <a href="http://takari.io/book/40-lifecycle.html#the-takari-lifecycle">The Takari Lifecycle</a> documentation for more details
   * @since 1.9
   */
  // TODO decide if 'forbiddenReference=error|ignore' is a better name, as in jdt project preferences
  @Parameter(defaultValue = "ignore")
  private AccessRulesViolation accessRulesViolation;

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

  @Parameter(defaultValue = "${project.dependencyArtifacts}", readonly = true)
  @Incremental(configuration = Configuration.ignore)
  private Set<Artifact> directDependencies;

  @Component
  private Map<String, AbstractCompiler> compilers;

  @Component
  private DefaultBuildContext context;

  public Charset getSourceEncoding() {
    return encoding == null ? null : Charset.forName(encoding);
  }

  private List<InputMetadata<File>> getSources() throws IOException, MojoExecutionException {
    List<InputMetadata<File>> sources = new ArrayList<InputMetadata<File>>();
    StringBuilder msg = new StringBuilder();
    for (String sourcePath : getSourceRoots()) {
      File sourceRoot = new File(sourcePath);
      msg.append("\n").append(sourcePath);
      if (!sourceRoot.isDirectory()) {
        msg.append("\n   does not exist or not a directory, skiped");
        continue;
      }
      // TODO this is a bug in project model, includes/excludes should be per sourceRoot
      Set<String> includes = getIncludes();
      if (includes == null || includes.isEmpty()) {
        includes = Collections.singleton("**/*.java");
      } else {
        for (String include : includes) {
          Set<String> illegal = new LinkedHashSet<>();
          if (!include.endsWith(Kind.SOURCE.extension)) {
            illegal.add(include);
          }
          if (!illegal.isEmpty()) {
            throw new MojoExecutionException(String.format("<includes> patterns must end with %s. Illegal patterns: %s", Kind.SOURCE.extension, illegal.toString()));
          }
        }
      }
      Set<String> excludes = getExcludes();
      int sourceCount = 0;
      for (InputMetadata<File> source : ((BuildContext) context).registerInputs(sourceRoot, includes, excludes)) {
        sources.add(source);
        sourceCount++;
      }
      if (log.isDebugEnabled()) {
        msg.append("\n   includes=").append(includes.toString());
        msg.append(" excludes=").append(excludes != null ? excludes.toString() : "[]");
        msg.append(" matched=").append(sourceCount);
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("Compile source roots:{}", msg);
    }
    return sources;
  }

  protected Set<File> getDirectDependencies() {
    Set<File> result = new LinkedHashSet<>();
    for (Artifact artofact : directDependencies) {
      result.add(artofact.getFile());
    }
    return result;
  }

  protected abstract Set<String> getSourceRoots();

  protected abstract Set<String> getIncludes();

  protected abstract Set<String> getExcludes();

  protected abstract File getOutputDirectory();

  protected abstract List<File> getClasspath();

  protected abstract File getGeneratedSourcesDirectory();

  protected abstract boolean isSkip();

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {

    Stopwatch stopwatch = new Stopwatch().start();

    if (isSkip()) {
      log.info("Skipping compilation");
      context.markSkipExecution();
      return;
    }

    final AbstractCompiler compiler = compilers.get(compilerId);
    if (compiler == null) {
      throw new MojoExecutionException("Unsupported compilerId" + compilerId);
    }

    try {
      final List<InputMetadata<File>> sources = getSources();
      if (sources.isEmpty()) {
        log.info("No sources, skipping compilation");
        return;
      }

      mkdirs(getOutputDirectory());
      if (proc != Proc.none) {
        mkdirs(getGeneratedSourcesDirectory());
      }

      compiler.setOutputDirectory(getOutputDirectory());
      compiler.setSource(source);
      compiler.setTarget(getTarget(target, source));
      compiler.setProc(proc);
      compiler.setGeneratedSourcesDirectory(getGeneratedSourcesDirectory());
      compiler.setAnnotationProcessors(annotationProcessors);
      compiler.setAnnotationProcessorOptions(annotationProcessorOptions);
      compiler.setVerbose(verbose);
      compiler.setPom(pom);
      compiler.setSourceEncoding(getSourceEncoding());
      compiler.setSourceRoots(getSourceRoots());
      compiler.setDebug(parseDebug(debug));
      compiler.setShowWarnings(showWarnings);
      compiler.setAccessRulesViolation(accessRulesViolation);

      if (compiler instanceof CompilerJavacLauncher) {
        ((CompilerJavacLauncher) compiler).setBasedir(basedir);
        ((CompilerJavacLauncher) compiler).setJar(pluginArtifact.getFile());
        ((CompilerJavacLauncher) compiler).setBuildDirectory(buildDirectory);
        ((CompilerJavacLauncher) compiler).setMeminitial(meminitial);
        ((CompilerJavacLauncher) compiler).setMaxmem(maxmem);
      }

      boolean classpathChanged = compiler.setClasspath(getClasspath(), getDirectDependencies());
      boolean sourcesChanged = compiler.setSources(sources);

      if (sourcesChanged || classpathChanged) {
        log.info("Compiling {} sources to {}", sources.size(), getOutputDirectory());
        compiler.compile();
        // TODO report actual number of sources compiled
        log.info("Compiled {} sources ({} ms)", sources.size(), stopwatch.elapsed(TimeUnit.MILLISECONDS));
      } else {
        // TODO this should be something like "cleanup after skipped compilation"
        compiler.skipCompilation();
        log.info("Skipped compilation, all {} sources are up to date", sources.size());
      }

      artifact.setFile(getOutputDirectory());

    } catch (IOException e) {
      throw new MojoExecutionException("Could not compile project", e);
    }
  }

  private static String getTarget(String target, String source) {
    if (target != null) {
      return target;
    }
    if (source != null) {
      if ("1.2".equals(source) || "1.3".equals(source)) {
        return "1.4";
      }
      return source;
    }
    return DEFAULT_COMPILER_LEVEL;
  }

  private static Set<Debug> parseDebug(String debug) {
    Set<Debug> result = new HashSet<AbstractCompileMojo.Debug>();
    StringTokenizer st = new StringTokenizer(debug, ",");
    while (st.hasMoreTokens()) {
      String token = st.nextToken();
      Debug keyword;
      if ("true".equalsIgnoreCase(token)) {
        keyword = Debug.all;
      } else if ("false".equalsIgnoreCase(token)) {
        keyword = Debug.none;
      } else {
        keyword = Debug.valueOf(token);
      }
      result.add(keyword);
    }
    if (result.size() > 1 && (result.contains(Debug.all) || result.contains(Debug.none))) {
      throw new IllegalArgumentException("'all' and 'none' must be used alone: " + debug);
    }
    return result;
  }

  private File mkdirs(File dir) throws MojoExecutionException {
    if (!dir.isDirectory() && !dir.mkdirs()) {
      throw new MojoExecutionException("Could not create directory " + dir);
    }
    return dir;
  }
}
