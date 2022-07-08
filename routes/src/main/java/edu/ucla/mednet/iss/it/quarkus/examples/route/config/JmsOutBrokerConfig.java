package edu.ucla.mednet.iss.it.quarkus.examples.route.config;

import edu.ucla.mednet.iss.it.quarkus.examples.utils.AmqConfig;
import io.smallrye.config.ConfigMapping;
import java.util.Optional;

@ConfigMapping(prefix = "activemq.outbound")
public interface JmsOutBrokerConfig extends AmqConfig {

  String name();

  Optional<String> type();

  Optional<Boolean> durable();

  Optional<String> subscriptionId();

  Optional<Boolean> shared();


}

