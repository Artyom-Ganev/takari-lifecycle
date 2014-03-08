package io.takari.maven.plugins.compiler.incremental;

import io.takari.incrementalbuild.BuildContext;
import io.takari.incrementalbuild.BuildContext.Input;
import io.takari.incrementalbuild.spi.DefaultBuildContext;

import java.io.File;
import java.nio.charset.Charset;
import java.util.*;

import javax.tools.*;
import javax.tools.Diagnostic.Kind;

import org.apache.maven.plugin.MojoExecutionException;

public class CompilerJavac {

  private static interface JavaCompilerFactory {
    public JavaCompiler acquire() throws MojoExecutionException;

    public void release(JavaCompiler compiler);
  }

  private static JavaCompilerFactory CACHE = new JavaCompilerFactory() {

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
      JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
      if (compiler == null) {
        throw new MojoExecutionException("No compiler is provided in this environment. "
            + "Perhaps you are running on a JRE rather than a JDK?");
      }
      return compiler;
    }

    @Override
    public void release(JavaCompiler compiler) {
      synchronized (compilers) {
        compilers.addFirst(compiler);
      }
    }

  };

  private final DefaultBuildContext<?> context;

  private final AbstractCompileMojo config;

  public CompilerJavac(DefaultBuildContext<?> context, AbstractCompileMojo config) {
    this.context = context;
    this.config = config;
  }

  public void compile(List<File> sources) throws MojoExecutionException {
    // java 6 limitations
    // - there is severe performance penalty using new JavaCompiler instance
    // - the same JavaCompiler cannot be used concurrently
    // - even different JavaCompiler instances can't do annotation processing concurrently

    // The workaround is two-fold
    // - reuse JavaCompiler instances, but not on the same thread (see CACHE impl)
    // - do not allow in-process annotation processing

    JavaCompiler compiler = CACHE.acquire();
    try {
      compile(compiler, sources);
    } finally {
      CACHE.release(compiler);
    }
  }

  private void compile(JavaCompiler compiler, List<File> sourceFiles) throws MojoExecutionException {
    final Charset sourceEncoding = config.getSourceEncoding();
    final DiagnosticCollector<JavaFileObject> diagnosticCollector =
        new DiagnosticCollector<JavaFileObject>();
    final StandardJavaFileManager standardFileManager =
        compiler.getStandardFileManager(diagnosticCollector, null, sourceEncoding);
    final InputMetadataIterable<File> sources =
        new InputMetadataIterable<File>(context.registerInputs(sourceFiles));
    final Iterable<? extends JavaFileObject> javaSources =
        standardFileManager.getJavaFileObjectsFromFiles(sources);

    boolean deleted = context.getRemovedInputs(File.class).iterator().hasNext();

    // javac does not provide information about inter-class dependencies
    // if any of the sources changed, all sources need to be recompiled
    if (sources.isUnmodified() && !deleted && config.getChangedDependencyTypes().isEmpty()) {
      return;
    }

    final Iterable<String> options = config.getCompilerOptions();
    final RecordingJavaFileManager recordingFileManager =
        new RecordingJavaFileManager(standardFileManager) {
          @Override
          protected void record(File outputFile) {
            context.processOutput(outputFile);
          }
        };

    final JavaCompiler.CompilationTask task = compiler.getTask(null, // Writer out
        recordingFileManager, // file manager
        diagnosticCollector, // diagnostic listener
        options, //
        null, // Iterable<String> classes to process by annotation processor(s)
        javaSources);

    task.call();

    for (JavaFileObject source : javaSources) {
      context.registerInput(new File(source.toUri())).process();
    }

    for (Diagnostic<? extends JavaFileObject> diagnostic : diagnosticCollector.getDiagnostics()) {
      JavaFileObject source = diagnostic.getSource();
      if (source != null) {
        Input<File> input = context.registerInput(new File(source.toUri())).process();
        input.addMessage((int) diagnostic.getLineNumber(), (int) diagnostic.getColumnNumber(),
            diagnostic.getMessage(null), toSeverity(diagnostic.getKind()), null);
      } else {
        Input<File> input = context.registerInput(config.getPom()).process();
        // TODO execution line/column
        input.addMessage(0, 0, diagnostic.getMessage(null), toSeverity(diagnostic.getKind()), null);
      }
    }
  }

  private BuildContext.Severity toSeverity(Kind kind) {
    return kind == Kind.ERROR ? BuildContext.Severity.ERROR : BuildContext.Severity.WARNING;
  }
}
