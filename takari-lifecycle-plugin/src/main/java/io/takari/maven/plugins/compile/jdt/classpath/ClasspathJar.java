/**
 * Copyright (c) 2014 Takari, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */
package io.takari.maven.plugins.compile.jdt.classpath;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;

public class ClasspathJar extends DependencyClasspathEntry implements ClasspathEntry {

  private final File file;
  private final ZipFile zipFile;

  private ClasspathJar(File file, ZipFile zipFile, Collection<String> packageNames, Collection<String> exportedPackages) throws IOException {
    super(packageNames, exportedPackages);
    this.file = file;
    this.zipFile = zipFile;
  }

  private static Set<String> getPackageNames(ZipFile zipFile) {
    Set<String> result = new HashSet<String>();
    for (Enumeration e = zipFile.entries(); e.hasMoreElements();) {
      ZipEntry entry = (ZipEntry) e.nextElement();
      String name = entry.getName();
      int last = name.lastIndexOf('/');
      while (last > 0) {
        name = name.substring(0, last);
        result.add(name);
        last = name.lastIndexOf('/');
      }
    }
    return result;
  }

  @Override
  public NameEnvironmentAnswer findType(String packageName, String binaryFileName) {
    if (!packageNames.contains(packageName)) {
      return null;
    }
    try {
      String qualifiedFileName = packageName + "/" + binaryFileName;
      ClassFileReader reader = ClassFileReader.read(this.zipFile, qualifiedFileName);
      if (reader != null) return new NameEnvironmentAnswer(reader, getAccessRestriction(packageName));
    } catch (ClassFormatException e) {
      // treat as if class file is missing
    } catch (IOException e) {
      // treat as if class file is missing
    }
    return null;
  }

  @Override
  public String toString() {
    return "Classpath for jar file " + file.getPath(); //$NON-NLS-1$
  }

  @Override
  public String getEntryName() {
    return file.getAbsolutePath();
  }

  public static ClasspathJar create(File file) throws IOException {
    ZipFile zipFile = new ZipFile(file);
    Set<String> packageNames = getPackageNames(zipFile);

    ZipEntry entry = zipFile.getEntry(PATH_EXPORT_PACKAGE);
    Collection<String> exportedPackages;
    if (entry != null) {
      try (InputStream is = zipFile.getInputStream(entry)) {
        exportedPackages = parseExportPackage(is);
      }
    } else {
      exportedPackages = null;
    }

    return new ClasspathJar(file, zipFile, packageNames, exportedPackages);
  }
}
