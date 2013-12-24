package io.tesla.maven.plugins.resources;

import io.tesla.maven.plugins.TeslaLifecycleMojo;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Properties;

import javax.inject.Inject;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@Mojo(name = "process-resources", defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public class ProcessResources extends TeslaLifecycleMojo {

  @Parameter(defaultValue = "${project.build.outputDirectory}", property = "resources.outputDirectory")
  protected File outputDirectory;

  @Parameter(defaultValue = "${basedir}")
  private File basedir;

  @Parameter(defaultValue = "${project.properties}")
  private Properties properties;

  @Inject
  private ResourcesProcessor processor;

  @Override
  protected void executeMojo() throws MojoExecutionException {
    process(project.getBuild().getResources(), outputDirectory);
  }

  protected void process(List<Resource> resources, File outputDirectory)
      throws MojoExecutionException {
    for (Resource resource : resources) {
      boolean filter = Boolean.parseBoolean(resource.getFiltering());
      File sourceDirectory = new File(resource.getDirectory());
      // Ensure the sourceDirectory is actually present before attempting to process any resources
      if (!sourceDirectory.exists()) {
        continue;
      }
      File targetDirectory;
      if (resource.getTargetPath() != null) {
        targetDirectory = new File(outputDirectory, resource.getTargetPath());
      } else {
        targetDirectory = outputDirectory;
      }
      try {
        if (filter) {
          processor.process(sourceDirectory, targetDirectory, resource.getIncludes(),
              resource.getExcludes(), properties);
        } else {
          processor.process(sourceDirectory, targetDirectory, resource.getIncludes(),
              resource.getExcludes());
        }
      } catch (IOException e) {
        throw new MojoExecutionException(e.getMessage(), e);
      }
    }
  }
}
