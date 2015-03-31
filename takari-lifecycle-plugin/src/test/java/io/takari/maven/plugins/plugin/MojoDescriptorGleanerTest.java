package io.takari.maven.plugins.plugin;

import static io.takari.maven.testing.TestMavenRuntime.newParameter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.ArtifactHandler;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

import io.takari.incrementalbuild.maven.testing.IncrementalBuildRule;
import io.takari.maven.plugins.plugin.model.MojoDescriptor;
import io.takari.maven.plugins.plugin.model.MojoParameter;
import io.takari.maven.plugins.plugin.model.MojoRequirement;
import io.takari.maven.plugins.plugin.model.PluginDescriptor;
import io.takari.maven.plugins.plugin.model.io.xpp3.PluginDescriptorXpp3Reader;
import io.takari.maven.testing.TestProperties;
import io.takari.maven.testing.TestResources;

public class MojoDescriptorGleanerTest {
  @Rule
  public final TestResources resources = new TestResources();

  @Rule
  public final IncrementalBuildRule mojos = new IncrementalBuildRule();

  public final TestProperties properties = new TestProperties();

  @Test
  public void testGleaner() throws Exception {
    File basedir = resources.getBasedir("plugin-descriptor/gleaner");
    MavenProject project = mojos.readMavenProject(basedir);
    addDependency(project, "apache-plugin-annotations-jar");
    mojos.executeMojo(project, "compile", newParameter("compilerId", "jdt"));
    mojos.executeMojo(project, "mojo-annotation-processor");

    MojoDescriptor d = readDescriptor(basedir, "glean_RequirementTypes");

    Assert.assertEquals("java.util.Map", getRequirement(d, "mapRequirement").getRole());
    Assert.assertEquals("java.util.List", getRequirement(d, "listRequirement").getRole());
    Assert.assertEquals("java.util.List", getParameter(d, "listParameter").getType());
    Assert.assertEquals("boolean", getParameter(d, "booleanParameter").getType());
    Assert.assertEquals("java.lang.String[]", getParameter(d, "arrayParameter").getType());
  }

  private MojoParameter getParameter(MojoDescriptor descriptor, String fieldName) {
    for (MojoParameter parameter : descriptor.getParameters()) {
      if (fieldName.equals(parameter.getName())) {
        return parameter;
      }
    }
    throw new AssertionError("No such parameter " + fieldName);
  }

  private MojoRequirement getRequirement(MojoDescriptor descriptor, String fieldName) {
    for (MojoRequirement requirement : descriptor.getRequirements()) {
      if (fieldName.equals(requirement.getFieldName())) {
        return requirement;
      }
    }
    throw new AssertionError("No such requirement " + fieldName);
  }

  private MojoDescriptor readDescriptor(File basedir, String classname) throws IOException, XmlPullParserException {
    try (InputStream is = new FileInputStream(new File(basedir, "target/generated-sources/annotations/io.takari.lifecycle.uts.plugindescriptor." + classname + ".mojo.xml"))) {
      PluginDescriptor descriptor = new PluginDescriptorXpp3Reader().read(is);
      Assert.assertEquals(1, descriptor.getMojos().size());
      return descriptor.getMojos().get(0);
    }
  }

  private void addDependency(MavenProject project, String property) throws Exception {
    File file = new File(properties.get(property));
    ArtifactHandler handler = mojos.getContainer().lookup(ArtifactHandler.class, "jar");
    Set<Artifact> artifacts = project.getArtifacts();
    DefaultArtifact artifact = new DefaultArtifact(file.getName(), file.getName(), "1.0", Artifact.SCOPE_COMPILE, "jar", null, handler);
    artifact.setFile(file);
    artifacts.add(artifact);
    project.setArtifacts(artifacts);
  }

}
