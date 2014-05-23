package io.takari.maven.plugins.configurator;

import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.configurator.AbstractComponentConfigurator;
import org.codehaus.plexus.component.configurator.ComponentConfigurationException;
import org.codehaus.plexus.component.configurator.ConfigurationListener;
import org.codehaus.plexus.component.configurator.converters.composite.ObjectWithFieldsConverter;
import org.codehaus.plexus.component.configurator.converters.special.ClassRealmConverter;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluator;
import org.codehaus.plexus.configuration.PlexusConfiguration;

public class MojoConfigurator extends AbstractComponentConfigurator {

  private static MojoConfigurationProcessor mojoConfigurationProcessor = new MojoConfigurationProcessor();

  @Override
  public void configureComponent(final Object mojoInstance, //
      final PlexusConfiguration pluginConfigurationFromMaven, //
      final ExpressionEvaluator evaluator, //
      final ClassRealm realm, //
      final ConfigurationListener listener) throws ComponentConfigurationException {
    converterLookup.registerConverter(new ClassRealmConverter(realm));
    PlexusConfiguration mojoConfiguration = mojoConfigurationProcessor.mojoConfigurationFor(mojoInstance, pluginConfigurationFromMaven);
    new ObjectWithFieldsConverter().processConfiguration(converterLookup, mojoInstance, realm, mojoConfiguration, evaluator, listener);
  }
}
