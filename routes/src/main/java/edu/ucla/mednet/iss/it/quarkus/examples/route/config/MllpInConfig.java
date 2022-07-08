package edu.ucla.mednet.iss.it.quarkus.examples.route.config;

import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "mllp.inbound")
public interface MllpInConfig {
  Integer port();
}