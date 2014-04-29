package io.takari.maven.plugins.compile;

import io.takari.incrementalbuild.spi.DefaultBuildContext;
import io.takari.maven.plugins.compile.AbstractCompileMojo.Proc;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.plugin.MojoExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractCompiler {

  protected final Logger log = LoggerFactory.getLogger(getClass());

  protected final DefaultBuildContext<?> context;

  private File outputDirectory;

  private String source;

  private String target;

  private Proc proc;

  private File generatedSourcesDirectory;

  private String[] annotationProcessors;

  private boolean verbose;

  private File pom;

  private Charset sourceEncoding;

  private Set<String> sourceRoots;

  private Map<String, String> annotationProcessorOptions;

  protected AbstractCompiler(DefaultBuildContext<?> context) {
    this.context = context;
  }

  public void setOutputDirectory(File outputDirectory) {
    this.outputDirectory = outputDirectory;
  }

  protected File getOutputDirectory() {
    return outputDirectory;
  };

  public void setSource(String source) {
    this.source = source;
  }

  protected String getSource() {
    return source;
  }

  public void setTarget(String target) {
    this.target = target;
  }

  protected String getTarget() {
    return target;
  }

  public void setProc(Proc proc) {
    this.proc = proc;
  }

  protected Proc getProc() {
    return proc;
  }

  public void setGeneratedSourcesDirectory(File generatedSourcesDirectory) {
    this.generatedSourcesDirectory = generatedSourcesDirectory;
  }

  protected File getGeneratedSourcesDirectory() {
    return generatedSourcesDirectory;
  }

  public void setAnnotationProcessors(String[] annotationProcessors) {
    this.annotationProcessors = annotationProcessors;
  }

  protected String[] getAnnotationProcessors() {
    return annotationProcessors;
  }

  public void setAnnotationProcessorOptions(Map<String, String> annotationProcessorOptions) {
    this.annotationProcessorOptions = annotationProcessorOptions;
  }

  protected Map<String, String> getAnnotationProcessorOptions() {
    return annotationProcessorOptions;
  }

  public void setVerbose(boolean verbose) {
    this.verbose = verbose;
  }

  protected boolean isVerbose() {
    return verbose;
  }

  public void setPom(File pom) {
    this.pom = pom;
  }

  protected File getPom() {
    return pom;
  }

  public void setSourceEncoding(Charset sourceEncoding) {
    this.sourceEncoding = sourceEncoding;
  }

  protected Charset getSourceEncoding() {
    return sourceEncoding;
  }

  public void setSourceRoots(Set<String> sourceRoots) {
    this.sourceRoots = sourceRoots;
  }

  protected Set<String> getSourceRoots() {
    return sourceRoots;
  }

  public abstract boolean setClasspath(List<File> dependencies) throws IOException;

  public abstract boolean setSources(List<File> sources) throws IOException;

  public abstract void compile() throws MojoExecutionException, IOException;

  public abstract void skipCompilation();
}
