package edu.ucla.mednet.iss.it.quarkus.examples.route;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.apache.camel.CamelContext;

import edu.ucla.mednet.iss.it.quarkus.examples.route.config.JmsAuditBrokerConfig;
import edu.ucla.mednet.iss.it.quarkus.examples.route.config.JmsInBrokerConfig;
import edu.ucla.mednet.iss.it.quarkus.examples.route.config.MllpInConfig;
import edu.ucla.mednet.iss.it.quarkus.examples.routebuilder.MllpToJmsRouteBuilder;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.AmqpUtils;

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
    errorHandler(defaultErrorHandler().allowRedeliveryWhileStopping(false));

    this.setRouteId("mllp-in");
   

    setSourceComponent("mllp");
    setListenAddress("0.0.0.0:" + mllpInConfig.port());


    setMaxConcurrentConsumers(mllpInConfig.maxConcurrentConsumers());

    if (mllpInConfig.receiveBufferSize().isPresent()) {
      setReceiveBufferSize(mllpInConfig.receiveBufferSize().get());
    }


    if (mllpInConfig.mllpErrorAckType().isPresent()) {
      setMllpErrorAckType(mllpInConfig.mllpErrorAckType().get());
    }

    if (mllpInConfig.jmsExceptionHandled().isPresent()) {
      setJmsExceptionHandled(mllpInConfig.jmsExceptionHandled().get());
    }

    setJmsExceptionShutdown(mllpInConfig.jmsExceptionShutdown());
    setJmsExceptionMximumRedeliveries(mllpInConfig.jmsExceptionMximumRedeliveries());

    setTargetDestinationTransacted(mllpInConfig.targetDestinationTransacted());

    setReuseAddress(mllpInConfig.reuseAddress());

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
    // Producer components should use pooled connectionfactory
    String jmsAuditComponent = "jms-audit";
    AmqpUtils.addSjms2NamedComponentToContext(context, jmsAuditComponent, auditBroker);

    setAuditComponent(jmsAuditComponent);

    super.configure();
  }
}
