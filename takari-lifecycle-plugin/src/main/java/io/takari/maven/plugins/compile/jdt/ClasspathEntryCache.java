package io.takari.maven.plugins.compile.jdt;

import io.takari.maven.plugins.compile.jdt.classpath.*;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.maven.execution.scope.MojoExecutionScoped;
import org.apache.maven.project.MavenProject;

@Named
@MojoExecutionScoped
public class ClasspathEntryCache {

  private static final Map<File, ClasspathEntry> CACHE = new HashMap<File, ClasspathEntry>();

  @Inject
  public ClasspathEntryCache(MavenProject project) {
    synchronized (CACHE) {
      // this is only needed for unit tests, but won't hurt in general
      CACHE.remove(normalize(new File(project.getBuild().getOutputDirectory())));
      CACHE.remove(normalize(new File(project.getBuild().getTestOutputDirectory())));
    }
  }

  public ClasspathEntry get(File location) {
    location = normalize(location);
    synchronized (CACHE) {
      ClasspathEntry entry = null;
      if (!CACHE.containsKey(location)) {
        if (location.isDirectory()) {
          entry = new ClasspathDirectory(location);
        } else if (location.isFile()) {
          try {
            entry = new ClasspathJar(location);
          } catch (IOException e) {
            // not a zip/jar, ignore
          }
        }
        CACHE.put(location, entry);
      } else {
        entry = CACHE.get(location);
      }
      return entry;
    }
  }

  private File normalize(File location) {
    try {
      location = location.getCanonicalFile();
    } catch (IOException e1) {
      location = location.getAbsoluteFile();
    }
    return location;
  }
}
