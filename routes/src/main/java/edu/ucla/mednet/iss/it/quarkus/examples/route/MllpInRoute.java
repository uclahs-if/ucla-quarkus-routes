package edu.ucla.mednet.iss.it.quarkus.examples.route;

import edu.ucla.mednet.iss.it.quarkus.examples.route.config.JmsAuditBrokerConfig;
import edu.ucla.mednet.iss.it.quarkus.examples.route.config.JmsInBrokerConfig;
import edu.ucla.mednet.iss.it.quarkus.examples.route.config.MllpInConfig;
import edu.ucla.mednet.iss.it.quarkus.examples.routebuilder.MllpToJmsRouteBuilder;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.AmqpUtils;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import org.apache.camel.CamelContext;

@ApplicationScoped
public class MllpInRoute extends MllpToJmsRouteBuilder {

  @Inject
  CamelContext context;

  @Inject
  MllpInConfig mllpInConfig;

  @Inject
  JmsAuditBrokerConfig auditBroker;

  @Inject
  JmsInBrokerConfig inboundBroker;




  /**
   * Inbound mllp route.
   * Receives from mllp and puts the body to an artemis broker.
   */
  @Override
  public void configure() throws Exception {
    this.setRouteId("mllp-in");

    setMllpPort(mllpInConfig.port());


    // Set up the outbound component
    String jmsInboundComponent = "jms-inbound";
    // Producer components should use amq connectionfactory

    AmqpUtils.addSjms2NamedComponentToContext(context, jmsInboundComponent, inboundBroker);



    setTargetComponent(jmsInboundComponent);
    setTargetDestinationName(inboundBroker.name());

    if (inboundBroker.type().isPresent()) {
      setTargetType(inboundBroker.type().get());
    }

    // Set up the audit component
    // Producer  components should use pooled connectionfactory
    String jmsAuditComponent = "jms-audit";
    AmqpUtils.addSjms2NamedComponentToContext(context, jmsAuditComponent, auditBroker);

    setAuditComponent(jmsAuditComponent);
    
    super.configure();
  }
}
