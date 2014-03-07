package io.takari.maven.plugins.compiler.incremental;

import io.takari.incrementalbuild.BuildContext;
import io.takari.incrementalbuild.BuildContext.Input;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.tools.Diagnostic;
import javax.tools.Diagnostic.Kind;
import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

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

  private final BuildContext context;

  private final AbstractCompileMojo config;

  private class RecordingJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
    protected RecordingJavaFileManager(StandardJavaFileManager fileManager) {
      super(fileManager);
    }

    @Override
    public FileObject getFileForOutput(Location location, String packageName, String relativeName,
        FileObject sibling) throws IOException {
      return record(super.getFileForOutput(location, packageName, relativeName, sibling), sibling);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(Location location, String className,
        javax.tools.JavaFileObject.Kind kind, FileObject sibling) throws IOException {
      return record(super.getJavaFileForOutput(location, className, kind, sibling), sibling);
    }

    private <T extends FileObject> T record(T fileObject, FileObject sibling) {
      // tooling API is rather vague about sibling. it "javac might provide
      // the originating source file as sibling" but this does not appear to be
      // guaranteed. even though sibling appear to be the source during the test,
      // the current implementation does not rely on this uncertain javac behaviour
      context.processOutput(new File(fileObject.toUri()));
      return fileObject;
    }
  }

  public CompilerJavac(BuildContext context, AbstractCompileMojo config) {
    this.context = context;
    this.config = config;
  }

  public void compile() throws MojoExecutionException {
    // java 6 limitations
    // - there is severe performance penalty using new JavaCompiler instance
    // - the same JavaCompiler cannot be used concurrently
    // - even different JavaCompiler instances can't do annotation processing concurrently

    // The workaround is two-fold
    // - reuse JavaCompiler instances, but not on the same thread (see CACHE impl)
    // - do not allow in-process annotation processing

    JavaCompiler compiler = CACHE.acquire();
    try {
      compile(compiler);
    } finally {
      CACHE.release(compiler);
    }
  }

  private void compile(JavaCompiler compiler) throws MojoExecutionException {
    final Charset sourceEncoding = config.getSourceEncoding();
    final DiagnosticCollector<JavaFileObject> diagnosticCollector =
        new DiagnosticCollector<JavaFileObject>();

    final StandardJavaFileManager standardFileManager =
        compiler.getStandardFileManager(diagnosticCollector, null, sourceEncoding);

    final Iterable<? extends JavaFileObject> fileObjects =
        standardFileManager.getJavaFileObjectsFromFiles(config.getSources());

    Iterable<String> options = buildCompilerOptions();

    final JavaCompiler.CompilationTask task = compiler.getTask(null, // Writer out
        new RecordingJavaFileManager(standardFileManager), // file manager
        diagnosticCollector, // diagnostic listener
        options, //
        null, // Iterable<String> classes to process by annotation processor(s)
        fileObjects);

    task.call();

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

  private int toSeverity(Kind kind) {
    return kind == Kind.ERROR ? BuildContext.SEVERITY_ERROR : BuildContext.SEVERITY_WARNING;
  }

  private Iterable<String> buildCompilerOptions() {
    List<String> options = new ArrayList<String>();

    // output directory
    options.add("-d");
    options.add(config.getOutputDirectory().getAbsolutePath());

    options.add("-target");
    options.add(config.getTarget());

    options.add("-source");
    options.add(config.getSource());

    return options;
  }
}
