package io.takari.maven.plugins.dependency.tree.serializer;

import org.eclipse.aether.graph.DependencyNode;

public interface TreeRenderer {
  void render(DependencyNode root);
}