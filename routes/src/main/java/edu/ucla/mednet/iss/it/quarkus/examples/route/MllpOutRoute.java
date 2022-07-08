package edu.ucla.mednet.iss.it.quarkus.examples.route;

import edu.ucla.mednet.iss.it.quarkus.examples.route.config.JmsAuditBrokerConfig;
import edu.ucla.mednet.iss.it.quarkus.examples.route.config.JmsOutBrokerConfig;
import edu.ucla.mednet.iss.it.quarkus.examples.route.config.MllpOutConfig;
import edu.ucla.mednet.iss.it.quarkus.examples.routebuilder.JmsToMllpRouteBuilder;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.AmqpUtils;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.apache.camel.CamelContext;

@ApplicationScoped
public class MllpOutRoute extends JmsToMllpRouteBuilder {
  @Inject
  CamelContext context;

  @Inject
  JmsAuditBrokerConfig auditBroker;

  @Inject
  JmsOutBrokerConfig outboundBrokerConfig;

  @Inject
  MllpOutConfig mllpOutConfig;

  /**
   * Outbound mllp route.
   * Receives from mllp and puts the body onto a queue named mllp.
   */
  @Override
  public void configure() throws Exception {
    setRouteId("mllp-out");

    // Set up the outbound component
    String jmsOutboundComponent = "jms-outbound";
    // Consumer components should use amq connectionfactory
    AmqpUtils.addSjms2NamedComponentToContext(context, jmsOutboundComponent, outboundBrokerConfig);

    setSourceComponent(jmsOutboundComponent);
    setSourceDestinationName(outboundBrokerConfig.name());
    if (outboundBrokerConfig.subscriptionId().isPresent()) {
      setSourceSubscriptionId(outboundBrokerConfig.subscriptionId().get());
    }
    // Set up the audit component
    // Producer  components should use pooled connectionfactory
    String jmsAuditComponent = "jms-audit";
    AmqpUtils.addSjms2NamedComponentToContext(context, jmsAuditComponent, auditBroker);
    setAuditComponent(jmsAuditComponent);

    // Set the MLLP destination
    setTargetAddress(mllpOutConfig.host(), mllpOutConfig.port());

    super.configure();
  }
}
