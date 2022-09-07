package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import javax.jms.ConnectionFactory;
import org.apache.camel.CamelContext;
import org.apache.camel.component.sjms2.Sjms2Component;
import org.apache.qpid.jms.JmsConnectionFactory;
import org.messaginghub.pooled.jms.JmsPoolConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AmqpUtils {
  static Logger log = LoggerFactory.getLogger(AmqpUtils.class);

  /**
   * Create a named sjms2 component including the jms health check.
   * @param context the camel context to add the component and health check to.
   * @param name the name of the camel component.
   * @param connectionFactory used in the sjms2 component and health check
   */
  public static void addSjms2NamedComponentToContext(CamelContext context, String name, ConnectionFactory connectionFactory) {
    if (null == context.hasComponent(name)) {

      context.getRegistry().bind(name + "-healthCheck", new JmsHealthCheck(name, connectionFactory));

      Sjms2Component component = new Sjms2Component();
      component.setConnectionFactory(connectionFactory);
      context.addComponent(name, component);
    } else {
      log.debug(name + ": is already in the camel context. This component was not updated.");
    }
  }

  /**
   * Create a named sjms2 component including the jms health check.
   * @param context the camel context to add the component and health check to.
   * @param name the name of the camel component.
   * @param amqConfig create a default pooled connection factory using the parameters provided.
   */
  public static void addSjms2NamedComponentToContext(CamelContext context, String name, AmqConfig amqConfig) {
    JmsConnectionFactory qpidConnectionFactory = new JmsConnectionFactory(amqConfig.url());

    if (amqConfig.username().isPresent()) {
      qpidConnectionFactory.setUsername(amqConfig.username().get());
    }
    if (amqConfig.password().isPresent()) {
      qpidConnectionFactory.setPassword(amqConfig.password().get());
    }

    JmsPoolConnectionFactory jmsPoolConnectionFactory = new JmsPoolConnectionFactory();
    jmsPoolConnectionFactory.setConnectionFactory(qpidConnectionFactory);

    addSjms2NamedComponentToContext(context, name, jmsPoolConnectionFactory);
  }
}