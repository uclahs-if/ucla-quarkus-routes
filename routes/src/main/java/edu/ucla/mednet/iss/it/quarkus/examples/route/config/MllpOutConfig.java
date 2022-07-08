package edu.ucla.mednet.iss.it.quarkus.examples.route.config;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "mllp.outbound")
public interface MllpOutConfig {
  String host();

  int port();
}