package io.takari.maven.plugins.compile.jdt.classpath;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.internal.compiler.util.Util;

// mostly copy&paste from tycho
public class JavaInstallation {
  private static final FilenameFilter POTENTIAL_ZIP_FILTER = new FilenameFilter() {
    @Override
    public boolean accept(File dir, String name) {
      return Util.isPotentialZipArchive(name);
    }
  };

  private final File javaHome;

  public JavaInstallation(File javaHome) {
    this.javaHome = javaHome;
  }

  /**
   * Returns default classpath associated with this java installation. The classpath includes bootstrap, extendion and endorsed entries.
   */
  public List<File> getClasspath() throws IOException {
    // See org.eclipse.jdt.internal.compiler.batch.Main.setPaths

    List<File> classpath = new ArrayList<File>();

    // boot classpath
    File directoryToCheck;
    if (isAppleJDK()) {
      directoryToCheck = new File(javaHome, "../Classes");
    } else {
      directoryToCheck = new File(javaHome, "lib");
    }
    scanForArchives(classpath, directoryToCheck);

    // endorsed libraries
    scanForArchives(classpath, new File(javaHome, "lib/endorsed"));

    // extension libraries
    scanForArchives(classpath, new File(javaHome, "lib/ext"));

    return classpath;
  }

  protected boolean isAppleJDK() {
    return System.getProperty("java.vendor").startsWith("Apple");
  }

  private void scanForArchives(List<File> classPathList, File dir) {
    if (dir.isDirectory()) {
      File[] zipFiles = dir.listFiles(POTENTIAL_ZIP_FILTER);
      if (zipFiles != null) {
        for (File zipFile : zipFiles) {
          classPathList.add(zipFile);
        }
      }
    }
  }

  public static JavaInstallation getDefault() {
    return new JavaInstallation(Util.getJavaHome());
  }
}
