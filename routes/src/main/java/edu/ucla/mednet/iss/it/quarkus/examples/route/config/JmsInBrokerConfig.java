package edu.ucla.mednet.iss.it.quarkus.examples.route.config;

import edu.ucla.mednet.iss.it.quarkus.examples.utils.AmqConfig;
import io.smallrye.config.ConfigMapping;
import java.util.Optional;

@ConfigMapping(prefix = "activemq.inbound")
public interface JmsInBrokerConfig extends AmqConfig {

  String name();

  Optional<String> type();

  Optional<String> subscriptionId();
}