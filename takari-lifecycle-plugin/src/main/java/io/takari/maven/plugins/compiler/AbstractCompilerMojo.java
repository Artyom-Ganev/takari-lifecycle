package io.takari.maven.plugins.compiler;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

import io.takari.maven.plugins.compiler.api.Compiler;
import io.takari.maven.plugins.compiler.api.CompilerConfiguration;
import io.takari.maven.plugins.compiler.api.CompilerError;
import io.takari.maven.plugins.compiler.api.CompilerException;
import io.takari.maven.plugins.compiler.api.CompilerMessage;
import io.takari.maven.plugins.compiler.api.CompilerNotImplementedException;
import io.takari.maven.plugins.compiler.api.CompilerResult;
import io.takari.maven.plugins.compiler.manager.CompilerManager;
import io.takari.maven.plugins.compiler.manager.NoSuchCompilerException;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;

/**
 * TODO: At least one step could be optimized, currently the plugin will do two scans of all the
 * source code if the compiler has to have the entire set of sources. This is currently the case for
 * at least the C# compiler and most likely all the other .NET compilers too.
 *
 * @author others
 * @author <a href="mailto:trygvis@inamo.no">Trygve Laugst&oslash;l</a>
 * @version $Id: AbstractCompilerMojo.java 1447251 2013-02-18 13:35:39Z olamy $
 * @since 2.0
 */
public abstract class AbstractCompilerMojo extends AbstractMojo {
  // ----------------------------------------------------------------------
  // Configurables
  // ----------------------------------------------------------------------

  /**
   * Indicates whether the build will continue even if there are compilation errors.
   *
   * @since 2.0.2
   */
  @Parameter(property = "maven.compiler.failOnError", defaultValue = "true")
  private boolean failOnError = true;

  /**
   * Set to <code>true</code> to include debugging information in the compiled class files.
   */
  @Parameter(property = "maven.compiler.debug", defaultValue = "true")
  private boolean debug = true;

  /**
   * Set to <code>true</code> to show messages about what the compiler is doing.
   */
  @Parameter(property = "maven.compiler.verbose", defaultValue = "false")
  private boolean verbose;

  /**
   * Sets whether to show source locations where deprecated APIs are used.
   */
  @Parameter(property = "maven.compiler.showDeprecation", defaultValue = "false")
  private boolean showDeprecation;

  /**
   * Set to <code>true</code> to optimize the compiled code using the compiler's optimization
   * methods.
   */
  @Parameter(property = "maven.compiler.optimize", defaultValue = "false")
  private boolean optimize;

  /**
   * Set to <code>true</code> to show compilation warnings.
   */
  @Parameter(property = "maven.compiler.showWarnings", defaultValue = "false")
  private boolean showWarnings;

  /**
   * The -source argument for the Java compiler.
   */
  @Parameter(property = "maven.compiler.source", defaultValue = "1.5")
  protected String source;

  /**
   * The -target argument for the Java compiler.
   */
  @Parameter(property = "maven.compiler.target", defaultValue = "1.5")
  protected String target;

  /**
   * The -encoding argument for the Java compiler.
   *
   * @since 2.1
   */
  @Parameter(property = "encoding", defaultValue = "${project.build.sourceEncoding}")
  private String encoding;

  /**
   * The compiler id of the compiler to use. See this <a href="non-javac-compilers.html">guide</a>
   * for more information.
   */
  @Parameter(property = "maven.compiler.compilerId", defaultValue = "javac")
  private String compilerId;

  /**
   * Version of the compiler to use, ex. "1.3", "1.5", if {@link #fork} is set to <code>true</code>.
   */
  @Parameter(property = "maven.compiler.compilerVersion")
  private String compilerVersion;

  /**
   * Allows running the compiler in a separate process. If <code>false</code> it uses the built in
   * compiler, while if <code>true</code> it will use an executable.
   */
  @Parameter(property = "maven.compiler.fork", defaultValue = "false")
  private boolean fork;

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
   * Sets the executable of the compiler to use when {@link #fork} is <code>true</code>.
   */
  @Parameter(property = "maven.compiler.executable")
  private String executable;

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
   * <p>
   * Sets the arguments to be passed to the compiler (prepending a dash) if {@link #fork} is set to
   * <code>true</code>.
   * </p>
   * <p>
   * This is because the list of valid arguments passed to a Java compiler varies based on the
   * compiler version.
   * </p>
   * <p>
   * To pass <code>-Xmaxerrs 1000 -Xlint -Xlint:-path -Averbose=true</code> you should include the
   * following:
   * </p>
   * 
   * <pre>
   * &lt;compilerArguments&gt;
   *   &lt;Xmaxerrs&gt;1000&lt;/Xmaxerrs&gt;
   *   &lt;Xlint/&gt;
   *   &lt;Xlint:-path/&gt;
   *   &lt;Averbose&gt;true&lt;/Averbose&gt;
   * &lt;/compilerArguments&gt;
   * </pre>
   *
   * @since 2.0.1
   * @deprecated use {@link #compilerArgs} instead.
   */
  @Parameter
  @Deprecated
  protected Map<String, String> compilerArguments;

  /**
   * <p>
   * Sets the arguments to be passed to the compiler if {@link #fork} is set to <code>true</code>.
   * Example:
   * 
   * <pre>
   * &lt;compilerArgs&gt;
   *   &lt;arg&gt;-Xmaxerrs=1000&lt;/arg&gt;
   *   &lt;arg&gt;-Xlint&lt;/arg&gt;
   * &lt;/compilerArgs&gt;
   * </pre>
   *
   * @since 3.1
   */
  @Parameter
  protected List<String> compilerArgs;

  /**
   * <p>
   * Sets the unformatted single argument string to be passed to the compiler if {@link #fork} is
   * set to <code>true</code>. To pass multiple arguments such as <code>-Xmaxerrs 1000</code> (which
   * are actually two arguments) you have to use {@link #compilerArguments}.
   * </p>
   * <p>
   * This is because the list of valid arguments passed to a Java compiler varies based on the
   * compiler version.
   * </p>
   */
  @Parameter
  protected String compilerArgument;

  /**
   * Keyword list to be appended to the <code>-g</code> command-line switch. Legal values are none
   * or a comma-separated list of the following keywords: <code>lines</code>, <code>vars</code>, and
   * <code>source</code>. If debug level is not specified, by default, nothing will be appended to
   * <code>-g</code>. If debug is not turned on, this attribute will be ignored.
   *
   * @since 2.1
   */
  @Parameter(property = "maven.compiler.debuglevel")
  private String debuglevel;

  @Component
  private CompilerManager compilerManager;

  // ----------------------------------------------------------------------
  // Read-only parameters
  // ----------------------------------------------------------------------

  /**
   * The directory to run the compiler from if fork is true.
   */
  @Parameter(defaultValue = "${basedir}", required = true, readonly = true)
  private File basedir;

  /**
   * The target directory of the compiler if fork is true.
   */
  @Parameter(defaultValue = "${project.build.directory}", required = true, readonly = true)
  private File buildDirectory;

  /**
   * The current build session instance. This is used for toolchain manager API calls.
   */
  @Component
  private MavenSession session;

  /**
   * Strategy to re use javacc class created:
   * <ul>
   * <li><code>reuseCreated</code> (default): will reuse already created but in case of
   * multi-threaded builds, each thread will have its own instance</li>
   * <li><code>reuseSame</code>: the same Javacc class will be used for each compilation even for
   * multi-threaded build</li>
   * <li><code>alwaysNew</code>: a new Javacc class will be created for each compilation</li>
   * </ul>
   * Note this parameter value depends on the os/jdk you are using, but the default value should
   * work on most of env.
   *
   * @since 2.5
   */
  @Parameter(defaultValue = "${reuseCreated}", property = "maven.compiler.compilerReuseStrategy")
  private String compilerReuseStrategy = "reuseCreated";

  /**
   * @since 3.0 needed for storing the status for the incremental build support.
   */
  @Parameter(property = "mojoExecution")
  private MojoExecution mojoExecution;

  /**
   * We need this to determine the start timestamp of the build.
   *
   * @since 3.0
   */
  @Component
  protected MavenSession mavenSession;

  /**
   * file extensions to check timestamp for incremental build <b>default contains only
   * <code>.class</code></b>
   *
   * @since 3.1
   */
  @Parameter
  private List<String> fileExtensions;

  protected abstract List<String> getClasspathElements();

  protected abstract List<String> getCompileSourceRoots();

  protected abstract File getOutputDirectory();

  protected abstract String getSource();

  protected abstract String getTarget();

  protected abstract String getCompilerArgument();

  protected abstract Map<String, String> getCompilerArguments();

  protected abstract File getGeneratedSourcesDirectory();

  public void execute() throws MojoExecutionException, CompilationFailureException {

    Compiler compiler;

    getLog().debug("Using compiler '" + compilerId + "'.");

    try {
      compiler = compilerManager.getCompiler(compilerId);
    } catch (NoSuchCompilerException e) {
      throw new MojoExecutionException("No such compiler '" + e.getCompilerId() + "'.");
    }

    List<String> compileSourceRoots = removeEmptyCompileSourceRoots(getCompileSourceRoots());
    if (compileSourceRoots.isEmpty()) {
      getLog().info("No sources to compile");
      return;
    }

    if (getLog().isDebugEnabled()) {
      getLog().debug("Source directories: " + compileSourceRoots.toString().replace(',', '\n'));
      getLog().debug("Classpath: " + getClasspathElements().toString().replace(',', '\n'));
      getLog().debug("Output directory: " + getOutputDirectory());
    }

    // ----------------------------------------------------------------------
    // Create the compiler configuration
    // ----------------------------------------------------------------------

    CompilerConfiguration compilerConfiguration = new CompilerConfiguration();
    compilerConfiguration.setOutputLocation(getOutputDirectory().getAbsolutePath());
    compilerConfiguration.setClasspathEntries(getClasspathElements());
    compilerConfiguration.setSourceLocations(compileSourceRoots);
    compilerConfiguration.setOptimize(optimize);
    compilerConfiguration.setDebug(debug);

    if (debug && StringUtils.isNotEmpty(debuglevel)) {
      String[] split = StringUtils.split(debuglevel, ",");
      for (int i = 0; i < split.length; i++) {
        if (!(split[i].equalsIgnoreCase("none") || split[i].equalsIgnoreCase("lines")
            || split[i].equalsIgnoreCase("vars") || split[i].equalsIgnoreCase("source"))) {
          throw new IllegalArgumentException("The specified debug level: '" + split[i]
              + "' is unsupported. " + "Legal values are 'none', 'lines', 'vars', and 'source'.");
        }
      }
      compilerConfiguration.setDebugLevel(debuglevel);
    }

    compilerConfiguration.setVerbose(verbose);
    compilerConfiguration.setShowWarnings(showWarnings);
    compilerConfiguration.setShowDeprecation(showDeprecation);
    compilerConfiguration.setSourceVersion(getSource());
    compilerConfiguration.setTargetVersion(getTarget());
    compilerConfiguration.setProc(proc);
    compilerConfiguration.setGeneratedSourcesDirectory(getGeneratedSourcesDirectory());
    compilerConfiguration.setAnnotationProcessors(annotationProcessors);
    compilerConfiguration.setSourceEncoding(encoding);

    Map<String, String> effectiveCompilerArguments = getCompilerArguments();
    String effectiveCompilerArgument = getCompilerArgument();

    if ((effectiveCompilerArguments != null) || (effectiveCompilerArgument != null)
        || (compilerArgs != null)) {
      LinkedHashMap<String, String> cplrArgsCopy = new LinkedHashMap<String, String>();
      if (effectiveCompilerArguments != null) {
        for (Map.Entry<String, String> me : effectiveCompilerArguments.entrySet()) {
          String key = me.getKey();
          String value = me.getValue();
          if (!key.startsWith("-")) {
            key = "-" + key;
          }

          if (key.startsWith("-A") && StringUtils.isNotEmpty(value)) {
            cplrArgsCopy.put(key + "=" + value, null);
          } else {
            cplrArgsCopy.put(key, value);
          }
        }
      }
      if (!StringUtils.isEmpty(effectiveCompilerArgument)) {
        cplrArgsCopy.put(effectiveCompilerArgument, null);
      }
      if (compilerArgs != null) {
        for (String arg : compilerArgs) {
          cplrArgsCopy.put(arg, null);
        }
      }
      compilerConfiguration.setCustomCompilerArguments(cplrArgsCopy);
    }

    compilerConfiguration.setFork(fork);

    if (fork) {
      if (!StringUtils.isEmpty(meminitial)) {
        String value = getMemoryValue(meminitial);

        if (value != null) {
          compilerConfiguration.setMeminitial(value);
        } else {
          getLog().info("Invalid value for meminitial '" + meminitial + "'. Ignoring this option.");
        }
      }

      if (!StringUtils.isEmpty(maxmem)) {
        String value = getMemoryValue(maxmem);

        if (value != null) {
          compilerConfiguration.setMaxmem(value);
        } else {
          getLog().info("Invalid value for maxmem '" + maxmem + "'. Ignoring this option.");
        }
      }
    }

    compilerConfiguration.setExecutable(executable);
    compilerConfiguration.setWorkingDirectory(basedir);
    compilerConfiguration.setCompilerVersion(compilerVersion);
    compilerConfiguration.setBuildDirectory(buildDirectory);

    if (CompilerConfiguration.CompilerReuseStrategy.AlwaysNew.getStrategy().equals(
        this.compilerReuseStrategy)) {
      compilerConfiguration
          .setCompilerReuseStrategy(CompilerConfiguration.CompilerReuseStrategy.AlwaysNew);
    } else if (CompilerConfiguration.CompilerReuseStrategy.ReuseSame.getStrategy().equals(
        this.compilerReuseStrategy)) {
      compilerConfiguration
          .setCompilerReuseStrategy(CompilerConfiguration.CompilerReuseStrategy.ReuseSame);
    } else {
      compilerConfiguration
          .setCompilerReuseStrategy(CompilerConfiguration.CompilerReuseStrategy.ReuseCreated);
    }

    getLog().debug(
        "CompilerReuseStrategy: " + compilerConfiguration.getCompilerReuseStrategy().getStrategy());

    // ----------------------------------------------------------------------
    // Dump configuration
    // ----------------------------------------------------------------------

    if (getLog().isDebugEnabled()) {
      getLog().debug("Classpath:");

      for (String s : getClasspathElements()) {
        getLog().debug(" " + s);
      }

      getLog().debug("Source roots:");

      for (String root : getCompileSourceRoots()) {
        getLog().debug(" " + root);
      }

      try {
        if (fork) {
          if (compilerConfiguration.getExecutable() != null) {
            getLog().debug("Excutable: ");
            getLog().debug(" " + compilerConfiguration.getExecutable());
          }
        }

        String[] cl = compiler.createCommandLine(compilerConfiguration);
        if (cl != null && cl.length > 0) {
          StringBuilder sb = new StringBuilder();
          sb.append(cl[0]);
          for (int i = 1; i < cl.length; i++) {
            sb.append(" ");
            sb.append(cl[i]);
          }
          getLog().debug("Command line options:");
          getLog().debug(sb);
        }
      } catch (CompilerException ce) {
        getLog().debug(ce);
      }
    }

    // ----------------------------------------------------------------------
    // Compile!
    // ----------------------------------------------------------------------

    if (StringUtils.isEmpty(compilerConfiguration.getSourceEncoding())) {
      getLog().warn(
          "File encoding has not been set, using platform encoding " + ReaderFactory.FILE_ENCODING
              + ", i.e. build is platform dependent!");
    }

    CompilerResult compilerResult;

    try {
      try {
        compilerResult = compiler.performCompile(compilerConfiguration);
      } catch (CompilerNotImplementedException cnie) {
        List<CompilerError> messages = compiler.compile(compilerConfiguration);
        compilerResult = convertToCompilerResult(messages);
      }
    } catch (Exception e) {
      // TODO: don't catch Exception
      throw new MojoExecutionException("Fatal error compiling", e);
    }

    List<CompilerMessage> warnings = new ArrayList<CompilerMessage>();
    List<CompilerMessage> errors = new ArrayList<CompilerMessage>();
    for (CompilerMessage message : compilerResult.getCompilerMessages()) {
      if (message.isError()) {
        errors.add(message);
      } else {
        warnings.add(message);
      }
    }

    if (failOnError && !compilerResult.isSuccess()) {
      if (!warnings.isEmpty()) {
        getLog().info("-------------------------------------------------------------");
        getLog().warn("COMPILATION WARNING : ");
        getLog().info("-------------------------------------------------------------");
        for (CompilerMessage warning : warnings) {
          getLog().warn(warning.toString());
        }
        getLog().info(warnings.size() + ((warnings.size() > 1) ? " warnings " : " warning"));
        getLog().info("-------------------------------------------------------------");
      }

      if (!errors.isEmpty()) {
        getLog().info("-------------------------------------------------------------");
        getLog().error("COMPILATION ERROR : ");
        getLog().info("-------------------------------------------------------------");
        for (CompilerMessage error : errors) {
          getLog().error(error.toString());
        }
        getLog().info(errors.size() + ((errors.size() > 1) ? " errors " : " error"));
        getLog().info("-------------------------------------------------------------");
      }

      if (!errors.isEmpty()) {
        throw new CompilationFailureException(errors);
      } else {
        throw new CompilationFailureException(warnings);
      }
    } else {
      for (CompilerMessage message : compilerResult.getCompilerMessages()) {
        getLog().warn(message.toString());
      }
    }
  }

  protected CompilerResult convertToCompilerResult(List<CompilerError> compilerErrors) {
    if (compilerErrors == null) {
      return new CompilerResult();
    }
    List<CompilerMessage> messages = new ArrayList<CompilerMessage>(compilerErrors.size());
    boolean success = true;
    for (CompilerError compilerError : compilerErrors) {
      messages.add(new CompilerMessage(compilerError.getFile(), compilerError.getKind(),
          compilerError.getStartLine(), compilerError.getStartColumn(), compilerError.getEndLine(),
          compilerError.getEndColumn(), compilerError.getMessage()));
      if (compilerError.isError()) {
        success = false;
      }
    }

    return new CompilerResult(success, messages);
  }

  protected Date getBuildStartTime() {
    try {
      Method getRequestMethod = session.getClass().getMethod("getRequest");
      Object mavenExecutionRequest = getRequestMethod.invoke(session);
      Method getStartTimeMethod = mavenExecutionRequest.getClass().getMethod("getStartTime");
      Date buildStartTime = (Date) getStartTimeMethod.invoke(mavenExecutionRequest);
      return buildStartTime;
    } catch (Exception e) {
      getLog().debug("unable to get start time for the current build: " + e.getMessage());
    }

    return new Date();
  }

  private String getMemoryValue(String setting) {
    String value = null;

    // Allow '128' or '128m'
    if (isDigits(setting)) {
      value = setting + "m";
    } else if ((isDigits(setting.substring(0, setting.length() - 1)))
        && (setting.toLowerCase().endsWith("m"))) {
      value = setting;
    }
    return value;
  }

  private boolean isDigits(String string) {
    for (int i = 0; i < string.length(); i++) {
      if (!Character.isDigit(string.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * @todo also in ant plugin. This should be resolved at some point so that it does not need to be
   *       calculated continuously - or should the plugins accept empty source roots as is?
   */
  private static List<String> removeEmptyCompileSourceRoots(List<String> compileSourceRootsList) {
    List<String> newCompileSourceRootsList = new ArrayList<String>();
    if (compileSourceRootsList != null) {
      // copy as I may be modifying it
      for (String srcDir : compileSourceRootsList) {
        if (!newCompileSourceRootsList.contains(srcDir) && new File(srcDir).exists()) {
          newCompileSourceRootsList.add(srcDir);
        }
      }
    }
    return newCompileSourceRootsList;
  }
}
