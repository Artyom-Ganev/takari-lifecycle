/**
 * Copyright (c) 2014 Takari, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package io.takari.maven.plugins.compile.javac;

import io.takari.incrementalbuild.BuildContext.InputMetadata;
import io.takari.incrementalbuild.BuildContext.OutputMetadata;
import io.takari.incrementalbuild.BuildContext.ResourceStatus;
import io.takari.incrementalbuild.spi.DefaultBuildContext;
import io.takari.incrementalbuild.spi.DefaultInputMetadata;
import io.takari.incrementalbuild.spi.DefaultOutputMetadata;
import io.takari.maven.plugins.compile.AbstractCompileMojo.AccessRulesViolation;
import io.takari.maven.plugins.compile.AbstractCompileMojo.Debug;
import io.takari.maven.plugins.compile.AbstractCompileMojo.Proc;
import io.takari.maven.plugins.compile.AbstractCompiler;
import io.takari.maven.plugins.compile.jdt.CompilerJdt;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class AbstractCompilerJavac extends AbstractCompiler {

  private final ProjectClasspathDigester digester;

  protected final List<InputMetadata<File>> sources = new ArrayList<InputMetadata<File>>();

  private String classpath;

  protected AbstractCompilerJavac(DefaultBuildContext<?> context, ProjectClasspathDigester digester) {
    super(context);
    this.digester = digester;
  }

  protected List<String> getCompilerOptions() {
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
    options.add(classpath);

    // http://docs.oracle.com/javase/7/docs/technotes/tools/windows/javac.html#implicit
    options.add("-implicit:none");

    switch (getProc()) {
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
    if (getProc() != Proc.none) {
      options.add("-s");
      options.add(getGeneratedSourcesDirectory().getAbsolutePath());

      if (getAnnotationProcessors() != null) {
        options.add("-processor");
        StringBuilder processors = new StringBuilder();
        for (String processor : getAnnotationProcessors()) {
          if (processors.length() > 0) {
            processors.append(',');
          }
          processors.append(processor);
        }
        options.add(processors.toString());
      }

      if (getAnnotationProcessorOptions() != null) {
        for (Map.Entry<String, String> option : getAnnotationProcessorOptions().entrySet()) {
          options.add("-A" + option.getKey() + "=" + option.getValue());
        }
      }
    }

    if (isVerbose()) {
      options.add("-verbose");
    }

    Set<Debug> debug = getDebug();
    if (debug == null || debug.contains(Debug.all)) {
      options.add("-g");
    } else if (debug.contains(Debug.none)) {
      options.add("-g:none");
    } else {
      StringBuilder keywords = new StringBuilder();
      for (Debug keyword : debug) {
        if (keywords.length() > 0) {
          keywords.append(',');
        }
        keywords.append(keyword.name());
      }
      options.add("-g:" + keywords.toString());
    }

    if (isShowWarnings()) {
      options.add("-Xlint:all");
    } else {
      options.add("-Xlint:none");
    }

    return options;
  }

  @Override
  public boolean setClasspath(List<File> dependencies, Set<File> directDependencies) throws IOException {
    if (log.isDebugEnabled()) {
      StringBuilder msg = new StringBuilder();
      for (File element : dependencies) {
        msg.append("\n   ").append(element);
      }
      log.debug("Compile classpath: {} entries{}", dependencies.size(), msg.toString());
    }

    StringBuilder cp = new StringBuilder();
    cp.append(getOutputDirectory().getAbsolutePath());
    for (File dependency : dependencies) {
      if (dependency != null) {
        if (cp.length() > 0) {
          cp.append(File.pathSeparatorChar);
        }
        cp.append(dependency.getAbsolutePath());
      }
    }
    this.classpath = cp.toString();

    return digester.digestDependencies(dependencies);
  }

  @Override
  public boolean setSources(List<InputMetadata<File>> sources) {
    this.sources.addAll(sources);

    // always register pom.xml. pom.xml is used to track message general compiler messages
    // if not registered, it will cause these messages to be lost during no-change rebuild
    context.registerInput(getPom());

    List<InputMetadata<File>> modifiedSources = new ArrayList<InputMetadata<File>>();
    List<InputMetadata<File>> inputs = new ArrayList<InputMetadata<File>>();
    for (InputMetadata<File> input : sources) {
      inputs.add(input);
      if (input.getStatus() != ResourceStatus.UNMODIFIED) {
        modifiedSources.add(input);
      }
    }
    Set<DefaultInputMetadata<File>> deletedSources = context.getRemovedInputs(File.class);

    Set<DefaultOutputMetadata> modifiedOutputs = new HashSet<DefaultOutputMetadata>();
    for (DefaultOutputMetadata output : context.getProcessedOutputs()) {
      ResourceStatus status = output.getStatus();
      if (status == ResourceStatus.MODIFIED || status == ResourceStatus.REMOVED) {
        modifiedOutputs.add(output);
      }
    }

    if (!context.isEscalated() && log.isDebugEnabled()) {
      StringBuilder inputsMsg = new StringBuilder("Modified inputs:");
      for (InputMetadata<File> input : modifiedSources) {
        inputsMsg.append("\n   ").append(input.getStatus()).append(" ").append(input.getResource());
      }
      for (InputMetadata<File> input : deletedSources) {
        inputsMsg.append("\n   ").append(input.getStatus()).append(" ").append(input.getResource());
      }
      log.debug(inputsMsg.toString());

      if (!modifiedOutputs.isEmpty()) {
        StringBuilder outputsMsg = new StringBuilder("Modified outputs:");
        for (OutputMetadata<File> output : modifiedOutputs) {
          outputsMsg.append("\n   ").append(output.getStatus()).append(" ").append(output.getResource());
        }
        log.debug(outputsMsg.toString());
      }
    }

    return !modifiedSources.isEmpty() || !deletedSources.isEmpty() || !modifiedOutputs.isEmpty();
  }

  @Override
  public void skipCompilation() {
    // javac does not track all input/output association
    // outputs generated by annotation processors don't have inputs, for example
    // need to manually carry-over output metadata
    // otherwise outputs are deleted during BuildContext#commit
    for (OutputMetadata<File> output : context.getProcessedOutputs()) {
      if (!output.getAssociatedInputs(File.class).iterator().hasNext()) {
        context.markOutputAsUptodate(output.getResource());
      }
    }
  }

  protected Collection<File> getSourceFiles() {
    Collection<File> files = new ArrayList<File>(sources.size());
    for (InputMetadata<File> input : sources) {
      files.add(input.getResource());
    }
    return files;
  }

  @Override
  public void setAccessRulesViolation(AccessRulesViolation accessRulesViolation) {
    if (accessRulesViolation == AccessRulesViolation.error) {
      String msg = String.format("Compiler %s does not support accessRulesViolation=error, use compilerId=%s", getCompilerId(), CompilerJdt.ID);
      throw new IllegalArgumentException(msg);
    }
  }

  protected abstract String getCompilerId();
}
