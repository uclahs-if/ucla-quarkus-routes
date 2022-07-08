package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import io.smallrye.config.WithDefault;
import java.util.Optional;

public interface AmqConfig {
  /**
   * The URL of the connection factory.
   */
  @WithDefault("amqp://localhost:61616")
  String url();

  Optional<String> username();

  Optional<String> password();
}

