package io.takari.maven.plugins.compile.javac;

import io.takari.incrementalbuild.BuildContext;
import io.takari.incrementalbuild.BuildContext.Input;
import io.takari.incrementalbuild.BuildContext.InputMetadata;
import io.takari.incrementalbuild.BuildContext.Output;
import io.takari.incrementalbuild.BuildContext.Resource;
import io.takari.incrementalbuild.spi.DefaultBuildContext;
import io.takari.maven.plugins.compile.javac.CompilerJavacForked.CompilerConfiguration;
import io.takari.maven.plugins.compile.javac.CompilerJavacForked.CompilerOutput;
import io.takari.maven.plugins.compile.javac.CompilerJavacForked.CompilerOutputProcessor;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ShutdownHookProcessDestroyer;

@Named(CompilerJavacLauncher.ID)
public class CompilerJavacLauncher extends AbstractCompilerJavac {

  public static final String ID = "forked-javac";

  private File jar;

  private File basedir;

  private File buildDirectory;

  private String meminitial;

  private String maxmem;

  @Inject
  public CompilerJavacLauncher(DefaultBuildContext<?> context, ProjectClasspathDigester digester) {
    super(context, digester);
  }

  @Override
  public void compile() throws IOException {
    File options = File.createTempFile("javac-forked", ".options", buildDirectory);
    File output = File.createTempFile("javac-forked", ".output", buildDirectory);
    compile(options, output);
    // don't delete temp files in case of an exception
    // they maybe useful to debug the problem
    options.delete();
    output.delete();
  }

  private void compile(File options, File output) throws IOException {
    context.deleteStaleOutputs(false);

    new CompilerConfiguration(getSourceEncoding(), getCompilerOptions(), getSourceFiles()).write(options);

    // use the same JVM as the one used to run Maven (the "java.home" one)
    String executable = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
    if (File.separatorChar == '\\') {
      executable = executable + ".exe";
    }

    CommandLine cli = new CommandLine(executable);

    // jvm options
    cli.addArguments(new String[] {"-cp", jar.getAbsolutePath()});
    if (meminitial != null) {
      cli.addArgument("-Xms" + meminitial);
    }
    if (maxmem != null) {
      cli.addArgument("-Xmx" + maxmem);
    }

    // main class and program arguments
    cli.addArgument(CompilerJavacForked.class.getName());
    cli.addArgument(options.getAbsolutePath(), false);
    cli.addArgument(output.getAbsolutePath(), false);

    DefaultExecutor executor = new DefaultExecutor();
    // ExecuteWatchdog watchdog = null;
    // if (forkedProcessTimeoutInSeconds > 0) {
    // watchdog = new ExecuteWatchdog(forkedProcessTimeoutInSeconds * 1000L);
    // executor.setWatchdog(watchdog);
    // }
    // best effort to avoid orphaned child process
    executor.setProcessDestroyer(new ShutdownHookProcessDestroyer());
    executor.setWorkingDirectory(basedir);

    log.debug("External java process command line:\n   {}", cli);
    try {
      executor.execute(cli); // this throws ExecuteException if process return code != 0
    } catch (ExecuteException e) {
      if (!log.isDebugEnabled()) {
        log.info("External java process command line:\n   {}", cli);
      }
      throw e;
    }

    final Map<File, Output<File>> looseOutputs = new HashMap<File, Output<File>>();
    final Map<File, Input<File>> inputs = new HashMap<File, Input<File>>();

    for (InputMetadata<File> source : sources) {
      inputs.put(source.getResource(), source.process());
    }

    CompilerOutput.process(output, new CompilerOutputProcessor() {
      @Override
      public void processOutput(File inputFile, File outputFile) {
        Input<File> input = inputs.get(inputFile);
        if (input != null) {
          input.associateOutput(outputFile);
        } else {
          looseOutputs.put(outputFile, context.processOutput(outputFile));
        }
      }

      @Override
      public void addMessage(String path, int line, int column, String message, BuildContext.Severity kind) {
        if (".".equals(path)) {
          // TODO
        } else {
          File file = new File(path);
          Resource<File> resource = inputs.get(file);
          if (resource == null) {
            resource = looseOutputs.get(file);
          }
          if (resource != null) {
            resource.addMessage(line, column, message, kind, null);
          } else {
            log.warn("Unexpected java resource {}", file);
          }
        }
      }
    });
  }

  public void setBasedir(File basedir) {
    this.basedir = basedir;
  }

  public void setJar(File jar) {
    this.jar = jar;
  }

  public void setBuildDirectory(File buildDirectory) {
    this.buildDirectory = buildDirectory;
  }

  public void setMeminitial(String meminitial) {
    this.meminitial = meminitial;
  }

  public void setMaxmem(String maxmem) {
    this.maxmem = maxmem;
  }
}
