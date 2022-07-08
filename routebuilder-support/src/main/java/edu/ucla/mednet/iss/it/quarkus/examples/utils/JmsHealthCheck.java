package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import java.util.Map;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import org.apache.camel.health.HealthCheckResultBuilder;
import org.apache.camel.impl.health.AbstractHealthCheck;

public class JmsHealthCheck extends AbstractHealthCheck {

  ConnectionFactory connectionFactory;

  /**
   * Set up the JmsHealthCheck with a unique id and the connection factory to check the status of.
   * @param id the name of the checkstyle.
   * @param connectionFactory the connection to the broker to check.
   */
  public JmsHealthCheck(String id, ConnectionFactory connectionFactory) {
    super("JmsHealthCheck", "UCLA JmsHealthCheck: " + id);
    this.connectionFactory = connectionFactory;
    getConfiguration().setEnabled(true);
  }

  @Override
  protected void doCall(HealthCheckResultBuilder builder, Map<String, Object> options) {
    try (Connection connection = connectionFactory.createConnection()) {
      builder.up();
    } catch (Exception exception) {
      builder.down();
    }
  }
}

