package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import java.util.Map;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Session;
import org.apache.camel.health.HealthCheckResultBuilder;
import org.apache.camel.impl.health.AbstractHealthCheck;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JmsHealthCheck extends AbstractHealthCheck {

  private static final Logger LOG = LoggerFactory.getLogger(JmsHealthCheck.class);

  ConnectionFactory connectionFactory;
  String id;

  /**
   * Set up the JmsHealthCheck with a unique id and the connection factory to check the status of.
   * @param id the name of the checkstyle.
   * @param connectionFactory the connection to the broker to check.
   */
  public JmsHealthCheck(String id, JmsConnectionFactory connectionFactory) {
    super("JmsHealthCheck", "UCLA JmsHealthCheck: " + id);
    this.connectionFactory = connectionFactory;
    this.id = id;
  }

  @Override
  protected void doCall(HealthCheckResultBuilder builder, Map<String, Object> options) {
    try (Connection connection = connectionFactory.createConnection(); Session session = connection.createSession()) {
      LOG.info("JMSHealthCheck " + id + ": UP");
      builder.up();
    } catch (Exception exception) {
      LOG.info("JMSHealthCheck " + id + ": DOWN");
      builder.down();
    }
  }
}



