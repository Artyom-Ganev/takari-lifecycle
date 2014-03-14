package io.takari.maven.plugins.compile.javac;

import io.takari.incrementalbuild.BuildContext;
import io.takari.incrementalbuild.BuildContext.Input;
import io.takari.incrementalbuild.BuildContext.Severity;
import io.takari.incrementalbuild.spi.DefaultBuildContext;
import io.takari.maven.plugins.compile.AbstractCompileMojo;
import io.takari.maven.plugins.compile.AbstractCompileMojo.Proc;
import io.takari.maven.plugins.compile.ProjectClasspathDigester;

import java.io.File;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;

import javax.tools.Diagnostic;
import javax.tools.Diagnostic.Kind;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import org.apache.maven.plugin.MojoExecutionException;

public class CompilerJavac extends AbstractCompilerJavac {

  private static final boolean isJava7;

  static {
    boolean isJava7x = true;
    try {
      Class.forName("java.nio.file.Files");
    } catch (Exception e) {
      isJava7x = false;
    }
    isJava7 = isJava7x;
  }

  static JavaCompiler getSystemJavaCompiler() throws MojoExecutionException {
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) {
      throw new MojoExecutionException("No compiler is provided in this environment. "
          + "Perhaps you are running on a JRE rather than a JDK?");
    }
    return compiler;
  }

  private static interface JavaCompilerFactory {
    public JavaCompiler acquire() throws MojoExecutionException;

    public void release(JavaCompiler compiler);
  }

  private static final JavaCompilerFactory REUSECREATED = new JavaCompilerFactory() {

    // TODO broken if this plugin is loaded by multiple classloaders
    // https://cwiki.apache.org/confluence/display/MAVEN/Maven+3.x+Class+Loading
    private final Deque<JavaCompiler> compilers = new ArrayDeque<JavaCompiler>();

    @Override
    public JavaCompiler acquire() throws MojoExecutionException {
      synchronized (compilers) {
        if (!compilers.isEmpty()) {
          return compilers.removeFirst();
        }
      }
      return getSystemJavaCompiler();
    }

    @Override
    public void release(JavaCompiler compiler) {
      synchronized (compilers) {
        compilers.addFirst(compiler);
      }
    }
  };

  private static final JavaCompilerFactory SINGLETON = new JavaCompilerFactory() {

    private JavaCompiler compiler;

    @Override
    public void release(JavaCompiler compiler) {}

    @Override
    public synchronized JavaCompiler acquire() throws MojoExecutionException {
      if (compiler == null) {
        compiler = getSystemJavaCompiler();
      }
      return compiler;
    }
  };

  public CompilerJavac(DefaultBuildContext<?> context, AbstractCompileMojo config,
      ProjectClasspathDigester digester) {
    super(context, config, digester);
  }

  @Override
  public void compile() throws MojoExecutionException {
    // java 6 limitations
    // - there is severe performance penalty using new JavaCompiler instance
    // - the same JavaCompiler cannot be used concurrently
    // - even different JavaCompiler instances can't do annotation processing concurrently

    // java 7 (and I assume newer) do not have these limitations

    // The workaround is two-fold
    // - reuse JavaCompiler instances, but not on multiple threads
    // - do not allow in-process annotation processing

    if (!isJava7 && config.getProc() != Proc.none) {
      // TODO maybe allow in single-threaded mode
      throw new MojoExecutionException("Annotation processing requires forked JVM on Java 6");
    }

    final JavaCompilerFactory factory = isJava7 ? SINGLETON : REUSECREATED;

    final JavaCompiler compiler = factory.acquire();
    try {
      compile(compiler);
    } finally {
      factory.release(compiler);
    }
  }

  private void compile(JavaCompiler compiler) {
    final Charset sourceEncoding = config.getSourceEncoding();
    final DiagnosticCollector<JavaFileObject> diagnosticCollector =
        new DiagnosticCollector<JavaFileObject>();
    final StandardJavaFileManager standardFileManager =
        compiler.getStandardFileManager(diagnosticCollector, null, sourceEncoding);
    final Iterable<? extends JavaFileObject> javaSources =
        standardFileManager.getJavaFileObjectsFromFiles(sources);

    final Iterable<String> options = getCompilerOptions();
    final RecordingJavaFileManager recordingFileManager =
        new RecordingJavaFileManager(standardFileManager) {
          @Override
          protected void record(File outputFile) {
            context.processOutput(outputFile);
          }
        };

    Writer stdout = new PrintWriter(System.out, true);
    final JavaCompiler.CompilationTask task = compiler.getTask(stdout, // Writer out
        recordingFileManager, // file manager
        diagnosticCollector, // diagnostic listener
        options, //
        null, // Iterable<String> classes to process by annotation processor(s)
        javaSources);

    boolean success = task.call();

    for (JavaFileObject source : javaSources) {
      context.registerInput(FileObjects.toFile(source)).process();
    }

    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnosticCollector.getDiagnostics()) {
      JavaFileObject source = diagnostic.getSource();

      // javac appears to report errors even when compilation was success.
      // I was only able to reproduce this with annotation processing on java 6
      // for consistency with forked mode, downgrade errors to warning here too
      Severity severity =
          success ? BuildContext.Severity.WARNING : toSeverity(diagnostic.getKind());

      if (source != null) {
        Input<File> input = context.registerInput(FileObjects.toFile(source)).process();
        input.addMessage((int) diagnostic.getLineNumber(), (int) diagnostic.getColumnNumber(),
            diagnostic.getMessage(null), severity, null);
      } else {
        Input<File> input = context.registerInput(config.getPom()).process();
        // TODO execution line/column
        input.addMessage(0, 0, diagnostic.getMessage(null), severity, null);
      }
    }
  }

  private BuildContext.Severity toSeverity(Kind kind) {
    return kind == Kind.ERROR ? BuildContext.Severity.ERROR : BuildContext.Severity.WARNING;
  }
}
