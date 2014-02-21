package io.tesla.maven.plugins.compiler;

import java.io.File;
import java.util.List;
import java.util.Set;

// XXX do we need both InternalCompilerConfiguration and AbstractInternalCompiler?
public interface InternalCompilerConfiguration {
  File getPom();

  List<String> getSourceRoots();

  Set<String> getSourceIncludes();

  Set<String> getSourceExcludes();

  File getOutputDirectory();

  List<String> getClasspathElements();

  String getSource();

  String getTarget();
}
