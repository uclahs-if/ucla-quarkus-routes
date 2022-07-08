package edu.ucla.mednet.iss.it.quarkus.examples.route.config;

import edu.ucla.mednet.iss.it.quarkus.examples.utils.AmqConfig;
import io.smallrye.config.ConfigMapping;

@ConfigMapping(prefix = "activemq.audit")
public interface JmsAuditBrokerConfig extends AmqConfig {

}