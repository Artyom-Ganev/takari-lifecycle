package io.takari.maven.plugins.resources;

import java.io.File;
import java.util.List;

import org.apache.maven.model.Resource;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

@Mojo(name = "process-resources", defaultPhase = LifecyclePhase.PROCESS_RESOURCES)
public class ProcessResources extends AbstractProcessResourcesMojo {

  @Parameter(defaultValue = "${project.build.outputDirectory}", property = "resources.outputDirectory")
  private File outputDirectory;

  @Parameter(defaultValue = "${project.build.resources}")
  private List<Resource> resources;

  @Override
  protected void executeMojo() throws MojoExecutionException {
    process(resources, outputDirectory);
  }

}
