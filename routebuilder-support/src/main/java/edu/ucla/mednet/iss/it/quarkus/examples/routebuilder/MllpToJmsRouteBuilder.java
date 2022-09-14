package edu.ucla.mednet.iss.it.quarkus.examples.routebuilder;

import java.util.StringJoiner;

import javax.jms.JMSException;

import org.apache.camel.LoggingLevel;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mllp.MllpConstants;
import org.apache.camel.model.OnCompletionDefinition;
import org.apache.camel.model.OnExceptionDefinition;
import org.apache.camel.model.RouteDefinition;

import edu.ucla.mednet.iss.it.quarkus.examples.utils.FailureAuditProcessor;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.Hl7AuditMessageProcessor;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.InboundAuditEnrichmentProcessor;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.KubernetesApi;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.LogHl7Body;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.SentMllpAcknowledgmentAuditProcessor;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.UclaHl7EnrichmentProcessor;

/**
 * Camel RouteBuilder for MLLP Receivers.
 */
public class MllpToJmsRouteBuilder extends RouteBuilder {
  static final String SYNC_SHUTDOWN_URI = "controlbus://route?action=stop&async=true&routeId=current";
  static final String DEFAULT_FAILURE_DESTINATION_NAME = "audit-failure";
  static final String DEFAULT_AUDIT_DESTINATION_NAME = "audit-in";

  static final String DEFAULT_SOURCE_COMPONENT = "mllp";
  static final String DEFAULT_ACKNOWLEDGEMENT_DESTINATION_NAME = "audit-ack";


  /*
    Standard route configuration
   */
  String containerName;
  String routeId;

  /*
    Unexpected Failure Options
   */
  boolean shutdownOnFailure = true;
  String failureComponent;
  String failureDestinationName = DEFAULT_FAILURE_DESTINATION_NAME;
  Processor auditFailureProcessor;

  /*
    HL7 Audit Processor
   */
  Hl7AuditMessageProcessor hl7AuditMessageProcessor;
  String auditDestinationName = DEFAULT_AUDIT_DESTINATION_NAME;

  /*
    Source configuration
   */
  String sourceComponent = DEFAULT_SOURCE_COMPONENT;
  String listenAddress = "0.0.0.0";

  Integer backlog;
  Integer bindTimeout;
  Integer bindRetryInterval;
  Integer acceptTimeout;
  Integer receiveTimeout;
  Integer idleTimeout;
  Integer readTimeout;
  Boolean keepAlive;
  Boolean tcpNoDelay;
  Boolean reuseAddress;
  Integer receiveBufferSize;
  Integer sendBufferSize;
  Boolean lenientBind = true;
  Boolean autoAck = true;
  Boolean hl7Headers;
  Boolean requireEndOfData;
  Boolean validatePayload = true;
  Boolean stringPayload;
  String charsetName;
  String targetType = "topic";
  Integer maxConcurrentConsumers;
  // Default to send AE's back to the sender. If this is set to an empty string a nak will not be sent.
  String  mllpErrorAckType = "AE";
  boolean jmsExceptionHandled = false;
  boolean jmsExceptionShutdown = false;
  int jmsExceptionMximumRedeliveries = 0;

  /*
    Audit configuration
   */
  String auditComponent;

  /*
    Target configuration
   */
  String targetComponent;
  String targetDestinationName;
  String targetDestinationTransacted = "false";

  /*
    Processing configuration
   */
  Processor auditEnrichmentProcessor;
  Processor payloadPreProcessor;
  Processor payloadProcessor;

  /*
    ACK Handling options
   */
  Processor auditAcknowledgementProcessor;
  String acknowledgmentDestinationName = DEFAULT_ACKNOWLEDGEMENT_DESTINATION_NAME;

  /**
   * Kubernetes Information.
   */
  KubernetesApi kubernetesApi;

  /**
   * Create an instance and configure the default processors.
   */
  public MllpToJmsRouteBuilder() {
    UclaHl7EnrichmentProcessor enrichmentProcessor = new UclaHl7EnrichmentProcessor();

    enrichmentProcessor.setRequired(true);
    enrichmentProcessor.setCleanPayload(false);
    enrichmentProcessor.setGenerateHeaders(true);
    enrichmentProcessor.setGenerateAcknowledgement(false);

    payloadProcessor = enrichmentProcessor;

    hl7AuditMessageProcessor = new Hl7AuditMessageProcessor();
    auditAcknowledgementProcessor = new SentMllpAcknowledgmentAuditProcessor();
    auditFailureProcessor = new FailureAuditProcessor();


    kubernetesApi = new KubernetesApi();
  }



  /**
   * Populate the registry and create the route.
   *
   * @throws Exception thrown if a creation error occurs
   */
  @Override
  public void configure() throws Exception {
    verifyConfiguration();

    errorHandler(defaultErrorHandler().allowRedeliveryWhileStopping(false).maximumRedeliveries(0));

    // Setup the exchange failure completion clause
    OnCompletionDefinition onFailure = onCompletion().id(routeId + ": onFailureOnly Handler")
        .onFailureOnly()
        .log(LoggingLevel.ERROR, "ON FAILURE EVENT.");
    if (hasFailureComponent() && hasFailureDestinationName()) {
      if (hasAuditFailureProcessor()) {
        onFailure = onFailure.process(getAuditFailureProcessor()).id(routeId + ": Prepare for audit");
      }
      onFailure = onFailure.toF("%s:queue:%s?transacted=false", getFailureComponent(), getFailureDestinationName()).id(routeId + ": Audit failure");
    }
    onFailure = onFailure .setProperty(MllpConstants.MLLP_ACKNOWLEDGEMENT_TYPE, simple(mllpErrorAckType));
    if (shutdownOnFailure) {
      onFailure.toF(SYNC_SHUTDOWN_URI).id(routeId + ": Stop Route on failure");
    }


    OnExceptionDefinition jmsAAExceptionDefinition =
        onException(JMSException.class).id(routeId + ": JMSException Error Handler after AA")
            .onWhen(exchangeProperty(MllpConstants.MLLP_ACKNOWLEDGEMENT_TYPE).isEqualTo("AA"))
            .handled(true)
            .maximumRedeliveries(0)
            .retriesExhaustedLogLevel(LoggingLevel.ERROR)
            .log(LoggingLevel.ERROR, "JMS ERROR Was caught after setting CamelMllpAcknowledgementType=AA message is not rolled back.");

    /*
     * JMS exceptions will shut the route down.
     * This will send out the ACK Type in mllpErrorAckType.
     * We expect openshift to restart the pod after this is shutdown.
     */
    OnExceptionDefinition jmsAEExceptionDefinition =
        onException(JMSException.class).id(routeId + ": JMSException Error Handler")
            .handled(jmsExceptionHandled)
            .maximumRedeliveries(jmsExceptionMximumRedeliveries)
            .retriesExhaustedLogLevel(LoggingLevel.ERROR)
            .logRetryAttempted(true)
            .log(LoggingLevel.ERROR, "JMS ERROR Caught Setting ACK Type to \"" + mllpErrorAckType + "\"")
            .setProperty(MllpConstants.MLLP_ACKNOWLEDGEMENT_TYPE, simple(mllpErrorAckType));
    if (jmsExceptionShutdown) {
      jmsAEExceptionDefinition = jmsAEExceptionDefinition.log(LoggingLevel.ERROR, "Shutting down route due to jmsExcpetionShutdown.")
                                                          .to(SYNC_SHUTDOWN_URI);
    }

    // Setup acknowledgment auditing
    OnCompletionDefinition onCompleteOnly =
        onCompletion().id(routeId + ": onCompleteOnly Completion Handler")
            .onCompleteOnly()
            .modeAfterConsumer()
            .log(LoggingLevel.INFO, "Setup auditing.");

    if (hl7AuditMessageProcessor != null) {
      onCompleteOnly = onCompleteOnly.bean(hl7AuditMessageProcessor);
    }
    // Audit the message
    onCompleteOnly = onCompleteOnly.toF("%s:queue:%s?exchangePattern=InOnly&transacted=false", getAuditComponent(), auditDestinationName).id(routeId + ": Audit Message");
    if (hasAuditAcknowledgementProcessor()) {
      onCompleteOnly = onCompleteOnly.process(auditAcknowledgementProcessor).id(routeId + ": Prepare acknowledgement for audit");
    }
    onCompleteOnly =  onCompleteOnly.log(LoggingLevel.INFO, "Setup auditing.")
        .bean(LogHl7Body.class)
        .toF("%s:queue:%s?exchangePattern=InOnly&transacted=false", getAuditComponent(), acknowledgmentDestinationName).id(routeId + ": Persist acknowledgement");


    RouteDefinition fromDefinition = fromF("%s://%s%s", sourceComponent, listenAddress, getSourceComponentOptions()).routeId(routeId);

    // Set the default ACK Type to an error. This will be set to AA if the route completes
    fromDefinition.setProperty(MllpConstants.MLLP_ACKNOWLEDGEMENT_TYPE, simple(mllpErrorAckType));

    if (hasAuditEnrichmentProcessor()) {
      fromDefinition = fromDefinition.process(auditEnrichmentProcessor).id(routeId + ": Audit Enrichment Processor").log(LoggingLevel.TRACE, "Audit Enrichment Processor");
    }
    
    fromDefinition = fromDefinition.process(kubernetesApi).id(routeId + ": Add Kubernetes Headers").log(LoggingLevel.TRACE, "Add Kubernetes Headers");

    if (hasPayloadPreProcessor()) {
      fromDefinition = fromDefinition.process(payloadPreProcessor).id(routeId + ": Payload Pre-Processor").log(LoggingLevel.TRACE, "Payload Pre-Processor");
    }

    if (hasPayloadProcessor()) {
      fromDefinition = fromDefinition.process(payloadProcessor).id(routeId + ": Payload Processor").log(LoggingLevel.TRACE, "Payload Processor");
    }

    if (hasStringPayload() && !getStringPayload()) {
      if (hasCharsetName()) {
        fromDefinition = fromDefinition.convertBodyTo(String.class, getCharsetName()).id(routeId + ": Convert body for Persistence using " + getCharsetName())
            .log(LoggingLevel.TRACE, "Convert body for Persistence using" + getCharsetName());
      } else {
        fromDefinition = fromDefinition.convertBodyTo(String.class).id(routeId + ": Convert body for Persistence")
            .log(LoggingLevel.TRACE, "Convert body for Persistence");
      }
    }

    // Log the message before sending to JMS.
    fromDefinition.bean(LogHl7Body.class);
    // Send the message to the jms target endpoint and audit endpoint.
    fromDefinition = fromDefinition.toF("%s:%s:%s?exchangePattern=InOnly&transacted=%s", targetComponent, targetType, targetDestinationName, targetDestinationTransacted);
    // Send an AA.
    fromDefinition.setProperty(MllpConstants.MLLP_ACKNOWLEDGEMENT_TYPE, simple("AA"));
  }


  public void setTargetDestinationTransacted(String targetDestinationTransacted) {
    this.targetDestinationTransacted = targetDestinationTransacted;
  }

  public void setJmsExceptionShutdown(boolean jmsExceptionShutdown) {
    this.jmsExceptionShutdown = jmsExceptionShutdown;
  }

  public void setJmsExceptionMximumRedeliveries(int jmsExceptionMximumRedeliveries) {
    this.jmsExceptionMximumRedeliveries = jmsExceptionMximumRedeliveries;
  }

  public void setJmsExceptionHandled(boolean jmsExceptionHandled) {
    this.jmsExceptionHandled = jmsExceptionHandled;
  }

  public boolean getjmsExceptionHandled() {
    return jmsExceptionHandled;
  }

  public boolean hasContainerName() {
    return containerName != null && containerName.isEmpty();
  }

  public String getContainerName() {
    return containerName;
  }

  /**
   * Configure the value of the container name.
   *
   * This property is use when configuring the default {@link InboundAuditEnrichmentProcessor}.  If this value is null or empty, the containerName property of the
   * default {@link InboundAuditEnrichmentProcessor} will not be configured by the RouteBuilder.
   *
   * The default value of this property is the value of the "karaf.name" system property.
   *
   * If a custom payload pre-processor has been configured, this value is unused.
   *
   * @param containerName the value to use for the containerName when configuring the default payload pre-processor
   */
  public void setContainerName(String containerName) {
    this.containerName = containerName;
  }

  public boolean hasRouteId() {
    return routeId != null && !routeId.isEmpty();
  }

  public String getRouteId() {
    return routeId;
  }

  /**
   * Configure the Camel Route ID.
   *
   * If this value is null or empty, the Camel Route ID will be derived from the source MQ-Series queue name, and the format of the ID is:
   *      mllp-receiver-{listenAddress}
   *
   * @param routeId the value to use for the Camel Route ID when configuring the route
   */
  public void setRouteId(String routeId) {
    this.routeId = routeId;
  }

  public boolean isShutdownOnFailure() {
    return shutdownOnFailure;
  }

  /**
   * Enable or disable use shutting down the route in the event of a failure.
   *
   * The default value of this property is 'false'.
   *
   * @param shutdownOnFailure if true, the route will be configured to shutdown on a failure
   */
  public void setShutdownOnFailure(boolean shutdownOnFailure) {
    this.shutdownOnFailure = shutdownOnFailure;
  }

  public boolean hasFailureComponent() {
    return (failureComponent != null && !failureComponent.isEmpty()) || hasAuditComponent();
  }

  public String getFailureComponent() {
    return (failureComponent != null && !failureComponent.isEmpty()) ? failureComponent : getAuditComponent();
  }

  /**
   * Configure the name of the SJMS component in the Camel registry to deliver failed exchanges.
   *
   * If this property is null or empty, the SJMS target component will be used.
   *
   * If the failureDestinationName property is null or empty, the failed exchange will not be delivered to another JMS destination.
   *
   * @param failureComponent the name of the component in the Camel registry
   */
  public void setFailureComponent(String failureComponent) {
    this.failureComponent = failureComponent;
  }

  public boolean hasFailureDestinationName() {
    return failureDestinationName != null && !failureDestinationName.isEmpty();
  }

  public String getFailureDestinationName() {
    return failureDestinationName;
  }

  /**
   * Configure the name of the JMS destination to deliver failed exchanges.
   *
   * If this property is null or empty, the failed exchange will not be delivered to any other JMS destinations.
   *
   * The default value of this property is 'audit.failure'.
   *
   * @param failureDestinationName the name JMS destination for failed exchange delivery
   */
  public void setFailureDestinationName(String failureDestinationName) {
    this.failureDestinationName = failureDestinationName;
  }

  public boolean hasAuditFailureProcessor() {
    return auditFailureProcessor != null;
  }

  public Processor getAuditFailureProcessor() {
    return auditFailureProcessor;
  }

  /**
   * Configure the Camel Processor used to prepare the exchange for delivery to the failure destination.
   *
   * By default, a {@link FailureAuditProcessor} is used.
   *
   * If this value is null, the exchange will be delivered to the failure destination without pre-processing.
   *
   * @param auditFailureProcessor the failure processor for preparing exchanges for deliver to the failure destination.
   */
  public void setAuditFailureProcessor(Processor auditFailureProcessor) {
    this.auditFailureProcessor = auditFailureProcessor;
  }

  public boolean hasListenAddress() {
    return listenAddress != null && !listenAddress.isEmpty();
  }

  public String getListenAddress() {
    return listenAddress;
  }

  /**
   * Configure the listening address for the source MLLP component.
   *
   * The format of the listening address is {hostname or ip}:{listening port}
   *
   * @param listenAddress the listening address
   */
  public void setListenAddress(String listenAddress) {
    this.listenAddress = listenAddress;
  }

  public boolean hasAuditEnrichmentProcessor() {
    return auditEnrichmentProcessor != null;
  }

  public Processor getAuditEnrichmentProcessor() {
    return auditEnrichmentProcessor;
  }

  /**
   * Configure the Camel processor to enrich the exchange with auditing headers.
   *
   * This processor is called immediately after a message is received from the external system.
   *
   * If this property is null, the RouteBuilder will create and use an {@link InboundAuditEnrichmentProcessor}.
   *
   * @param auditEnrichmentProcessor the enrichment processor
   */
  public void setAuditEnrichmentProcessor(Processor auditEnrichmentProcessor) {
    this.auditEnrichmentProcessor = auditEnrichmentProcessor;
  }

  public boolean hasPayloadPreProcessor() {
    return payloadPreProcessor != null;
  }

  public Processor getPayloadPreProcessor() {
    return payloadPreProcessor;
  }

  /**
   * Configure the Camel Processor that will process the exchange after the enrichment processor.
   *
   * If this value is null, the exchange will be not be pre-processed.
   *
   * By default, the property is null.
   *
   * @param payloadPreProcessor the  processor for.
   */
  public void setPayloadPreProcessor(Processor payloadPreProcessor) {
    this.payloadPreProcessor = payloadPreProcessor;
  }

  public boolean hasPayloadProcessor() {
    return payloadProcessor != null;
  }

  public Processor getPayloadProcessor() {
    return payloadProcessor;
  }

  /**
   * Configure the Camel Processor that to process the exchange before delivery to the target endpoint.
   *
   * By default, a {@link UclaHl7EnrichmentProcessor} is used.
   *
   * If this property is explicitly set to null, the exchange will be delivered to the target endpoint as-is.
   *
   * @param payloadProcessor the payload processor
   */
  public void setPayloadProcessor(Processor payloadProcessor) {
    this.payloadProcessor = payloadProcessor;
  }

  public boolean hasAuditAcknowledgementProcessor() {
    return auditAcknowledgementProcessor != null;
  }

  public Processor getAuditAcknowledgementProcessor() {
    return auditAcknowledgementProcessor;
  }

  /**
   * Configure the Camel Processor used to prepare the exchange for delivery to the audit destination.
   *
   * If this value is null, a {@link SentMllpAcknowledgmentAuditProcessor} is used.
   *
   * @param auditAcknowledgementProcessor the processor for preparing exchanges for delivery to the audit destination.
   */
  public void setAuditAcknowledgementProcessor(Processor auditAcknowledgementProcessor) {
    this.auditAcknowledgementProcessor = auditAcknowledgementProcessor;
  }

  public boolean hasSourceComponent() {
    return sourceComponent != null && !sourceComponent.isEmpty();
  }

  public String getSourceComponent() {
    return sourceComponent;
  }

  /**
   * Configure the name of the MLLP component in the Camel registry for source messages.
   *
   * @param sourceComponent the name of the source component in the Camel registry
   */
  public void setSourceComponent(String sourceComponent) {
    this.sourceComponent = sourceComponent;
  }

  public boolean hasAuditComponent() {
    return (auditComponent != null && !auditComponent.isEmpty()) || hasTargetComponent();
  }

  public String getAuditComponent() {
    return (auditComponent != null && !auditComponent.isEmpty()) ? auditComponent : getTargetComponent();
  }

  /**
   * Configure the name of the SJMS component in the Camel registry for source messages.
   *
   * @param auditComponent the name of the audit component in the Camel registry
   */
  public void setAuditComponent(String auditComponent) {
    this.auditComponent = auditComponent;
  }


  public boolean hasTargetComponent() {
    return targetComponent != null && !targetComponent.isEmpty();
  }

  public String getTargetComponent() {
    return targetComponent;
  }

  /**
   * Configure the name of the SJMS component in the Camel registry as a target for messages received from the MQ-Series Queue.
   *
   * @param targetComponent the name of the target component in the Camel registry
   */
  public void setTargetComponent(String targetComponent) {
    this.targetComponent = targetComponent;
  }

  public boolean hasMessageDestinationName() {
    return targetDestinationName != null && !targetDestinationName.isEmpty();
  }

  public String getTargetDestinationName() {
    return targetDestinationName;
  }

  /**
   * Configure the name of the JMS destination to use as a target for messages received from the external system.
   *
   * @param targetDestinationName the JMS destination name
   */
  public void setTargetDestinationName(String targetDestinationName) {
    this.targetDestinationName = targetDestinationName;
  }

  public boolean hasAcknowledgmentDestinationName() {
    return acknowledgmentDestinationName != null && !acknowledgmentDestinationName.isEmpty();
  }

  public String getAcknowledgmentDestinationName() {
    return acknowledgmentDestinationName;
  }

  /**
   * Configure the name of the JMS destination to use as a target for acknowledgements delivered to the external system.
   *
   * @param acknowledgmentDestinationName the JMS destination name
   */
  public void setAcknowledgmentDestinationName(String acknowledgmentDestinationName) {
    this.acknowledgmentDestinationName = acknowledgmentDestinationName;
  }

  /**
   * Determine if any MLLP component options have been set.
   *
   * @return true of component options have been set; false otherwise
   */
  public boolean hasSourceComponentOptions() {
    return hasBacklog()
        || hasBindTimeout()
        || hasBindRetryInterval()
        || hasAcceptTimeout()
        || hasReceiveTimeout()
        || hasIdleTimeout()
        || hasReadTimeout()
        || hasKeepAlive()
        || hasTcpNoDelay()
        || hasReuseAddress()
        || hasReceiveBufferSize()
        || hasSendBufferSize()
        || hasLenientBind()
        || hasAutoAck()
        || hasHl7Headers()
        || hasRequireEndOfData()
        || hasValidatePayload()
        || hasStringPayload()
        || hasCharsetName();
  }

  /**
   * Generate the MLLP component URI option String for the configured options.
   *
   * @return the MLLP component URI option String
   */
  public String getSourceComponentOptions() {
    StringJoiner componentOptions = new StringJoiner("&", "?", "");
    componentOptions.setEmptyValue("");

    if (hasBacklog()) {
      componentOptions.add("backlog=" + backlog);
    }
    if (hasBindTimeout()) {
      componentOptions.add("bindTimeout=" + bindTimeout);
    }
    if (hasBindRetryInterval()) {
      componentOptions.add("bindRetryInterval=" + bindRetryInterval);
    }
    if (hasAcceptTimeout()) {
      componentOptions.add("acceptTimeout=" + acceptTimeout);
    }
    if (hasReceiveTimeout()) {
      componentOptions.add("receiveTimeout=" + receiveTimeout);
    }
    if (hasIdleTimeout()) {
      componentOptions.add("idleTimeout=" + idleTimeout);
    }
    if (hasReadTimeout()) {
      componentOptions.add("readTimeout=" + readTimeout);
    }
    if (hasKeepAlive()) {
      componentOptions.add("keepAlive=" + keepAlive);
    }
    if (hasTcpNoDelay()) {
      componentOptions.add("tcpNoDelay=" + tcpNoDelay);
    }
    if (hasReuseAddress()) {
      componentOptions.add("reuseAddress=" + reuseAddress);
    }
    if (hasReceiveBufferSize()) {
      componentOptions.add("receiveBufferSize=" + receiveBufferSize);
    }
    if (hasSendBufferSize()) {
      componentOptions.add("sendBufferSize=" + sendBufferSize);
    }
    if (hasLenientBind()) {
      componentOptions.add("lenientBind=" + lenientBind);
    }
    if (hasAutoAck()) {
      componentOptions.add("autoAck=" + autoAck);
    }
    if (hasHl7Headers()) {
      componentOptions.add("hl7Headers=" + hl7Headers);
    }
    if (hasRequireEndOfData()) {
      componentOptions.add("requireEndOfData=" + requireEndOfData);
    }
    if (hasValidatePayload()) {
      componentOptions.add("validatePayload=" + validatePayload);
    }
    if (hasStringPayload()) {
      componentOptions.add("stringPayload=" + stringPayload);
    }
    if (hasCharsetName()) {
      componentOptions.add("charsetName=" + charsetName);
    }

    if(hasMaxConcurrentConsumers()) {
      componentOptions.add("maxConcurrentConsumers=" + maxConcurrentConsumers);
    }

    return componentOptions.toString();
  }

    private boolean hasMaxConcurrentConsumers() {
    return maxConcurrentConsumers != null;
  }

  public void setMaxConcurrentConsumers(Integer maxConcurrentConsumers) {
    this.maxConcurrentConsumers = maxConcurrentConsumers;
  }

  public Integer getMaxConcurrentConsumers() {
    return maxConcurrentConsumers;
  }

  public boolean hasBacklog() {
    return backlog != null;
  }

  public Integer getBacklog() {
    return backlog;
  }

  /**
   * Configure the backlog for the MLLP component.
   *
   * If specified, this property will be used to set the backlog parameter of the MLLP component.
   *
   * If this value is null, the backlog parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param backlog the backlog for the TCP server.  If null, use the default from the MLLP component
   */
  public void setBacklog(Integer backlog) {
    this.backlog = backlog;
  }

  public boolean hasBindTimeout() {
    return bindTimeout != null;
  }

  public Integer getBindTimeout() {
    return bindTimeout;
  }

  /**
   * Configure the bindTimeout for the MLLP component.
   *
   * If specified, this property will be used to set the bindTimeout parameter of the MLLP component.
   *
   * If this value is null, the bindTimeout parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param bindTimeout the bind timeout (in milliseconds) for the TCP server.  If null, use the default from the MLLP component
   */
  public void setBindTimeout(Integer bindTimeout) {
    this.bindTimeout = bindTimeout;
  }

  public boolean hasBindRetryInterval() {
    return bindRetryInterval != null;
  }

  public Integer getBindRetryInterval() {
    return bindRetryInterval;
  }

  /**
   * Configure the bindRetryInterval for the MLLP component.
   *
   * If specified, this property will be used to set the bindRetryInterval parameter of the MLLP component.
   *
   * If this value is null, the bindRetryInterval parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param bindRetryInterval the bind retry interval (in milliseconds) for the TCP server.  If null, use the default from the MLLP component
   */
  public void setBindRetryInterval(Integer bindRetryInterval) {
    this.bindRetryInterval = bindRetryInterval;
  }

  public boolean hasAcceptTimeout() {
    return acceptTimeout != null;
  }

  public Integer getAcceptTimeout() {
    return acceptTimeout;
  }

  /**
   * Configure the acceptTimeout for the MLLP component.
   *
   * If specified, this property will be used to set the acceptTimeout parameter of the MLLP component.
   *
   * If this value is null, the acceptTimeout parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param acceptTimeout the accept timeout (in milliseconds) for the TCP server.  If null, use the default from the MLLP component
   */
  public void setAcceptTimeout(Integer acceptTimeout) {
    this.acceptTimeout = acceptTimeout;
  }

  public Boolean hasReceiveTimeout() {
    return receiveTimeout != null;
  }

  public Integer getReceiveTimeout() {
    return receiveTimeout;
  }

  /**
   * Configure the receiveTimeout for the MLLP component.
   *
   * If specified, this property will be used to set the receiveTimeout parameter of the MLLP component.
   *
   * If this value is null, the receiveTimeout parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param receiveTimeout the receive timeout (in milliseconds) for the TCP server.  If null, use the default from the MLLP component
   */
  public void setReceiveTimeout(Integer receiveTimeout) {
    this.receiveTimeout = receiveTimeout;
  }

  public boolean hasIdleTimeout() {
    return idleTimeout != null;
  }

  public Integer getIdleTimeout() {
    return idleTimeout;
  }

  /**
   * Configure the idleTimeout for the MLLP component.
   *
   * If specified, this property will be used to set the idleTimeout parameter of the MLLP component.
   *
   * If this value is null, the idleTimeout parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param idleTimeout the idle timeout (in milliseconds) for the TCP server.  If null, use the default from the MLLP component
   */
  public void setIdleTimeout(Integer idleTimeout) {
    this.idleTimeout = idleTimeout;
  }

  public boolean hasReadTimeout() {
    return readTimeout != null;
  }

  public Integer getReadTimeout() {
    return readTimeout;
  }

  /**
   * Configure the readTimeout for the MLLP component.
   *
   * This parameter determines how long the MLLP component will wait to receive data after the initial packet has been received.  If this timeout is
   * exceeded, the MLLP component will raise an error for an incomplete message.
   *
   * If specified, this property will be used to set the readTimeout parameter of the MLLP component.
   *
   * If this value is null, the readTimeout parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param readTimeout the read timeout (in milliseconds) for the TCP server.  If null, use the default from the MLLP component
   */
  public void setReadTimeout(Integer readTimeout) {
    this.readTimeout = readTimeout;
  }

  public boolean hasKeepAlive() {
    return keepAlive != null;
  }

  public Boolean getKeepAlive() {
    return keepAlive;
  }

  /**
   * Configure TCP "keep alive" for the MLLP component.
   *
   * If specified, this property will be used to set the keepAlive parameter of the MLLP component.
   *
   * If this value is null, the keepAlive parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param keepAlive if true; enable TCP keep-alive.  If false, disable TCP keep-alive.  If null, use the default from the MLLP component.
   */
  public void setKeepAlive(Boolean keepAlive) {
    this.keepAlive = keepAlive;
  }

  public boolean hasTcpNoDelay() {
    return tcpNoDelay != null;
  }

  public Boolean getTcpNoDelay() {
    return tcpNoDelay;
  }

  /**
   * Configure TCP "no delay" for the MLLP component.
   *
   * If specified, this property will be used to set the tcpNoDelay parameter of the MLLP component.
   *
   * If this value is null, the tcpNoDelay parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param tcpNoDelay if true; enable TCP no-delay.  If false, disable TCP no-delay.  If null, use the default from the MLLP component.
   */
  public void setTcpNoDelay(Boolean tcpNoDelay) {
    this.tcpNoDelay = tcpNoDelay;
  }

  public boolean hasReuseAddress() {
    return reuseAddress != null;
  }

  public Boolean getReuseAddress() {
    return reuseAddress;
  }

  /**
   * Configure TCP the reuse of TCP sockets by the MLLP component.
   *
   * If specified, this property will be used to set the reuseAddress parameter of the MLLP component.
   *
   * If this value is null, the reuseAddress parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param reuseAddress if true; enable address reuse.  If false, disable address reuse.  If null, use the default from the MLLP component.
   */
  public void setReuseAddress(Boolean reuseAddress) {
    this.reuseAddress = reuseAddress;
  }

  public boolean hasReceiveBufferSize() {
    return receiveBufferSize != null;
  }

  public Integer getReceiveBufferSize() {
    return receiveBufferSize;
  }

  /**
   * Configure the size of the TCP receive buffer for the MLLP component.
   *
   * If specified, this property will be used to set the receiveBufferSize parameter of the MLLP component.
   *
   * If this value is null, the receiveBufferSize parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param receiveBufferSize the size of the TCP receive buffer (in bytes).  If null, use the default from the MLLP component.
   */
  public void setReceiveBufferSize(Integer receiveBufferSize) {
    this.receiveBufferSize = receiveBufferSize;
  }

  public boolean hasSendBufferSize() {
    return sendBufferSize != null;
  }

  public Integer getSendBufferSize() {
    return sendBufferSize;
  }

  /**
   * Configure the size of the TCP send buffer for the MLLP component.
   *
   * If specified, this property will be used to set the sendBufferSize parameter of the MLLP component.
   *
   * If this value is null, the sendBufferSize parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param sendBufferSize the size of the TCP receive buffer (in bytes).  If null, use the default from the MLLP component.
   */
  public void setSendBufferSize(Integer sendBufferSize) {
    this.sendBufferSize = sendBufferSize;
  }

  public boolean hasLenientBind() {
    return lenientBind != null;
  }

  public Boolean getLenientBind() {
    return lenientBind;
  }

  /**
   * Configure "lenient binding" for the MLLP component.
   *
   * If specified, this property will be used to set the lenientBind parameter of the MLLP component.
   *
   * If this value is null, the lenientBind parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param lenientBind if true; enable "lenient binding".  If false, disable "lenient binding".  If null, use the default from the MLLP component.
   */
  public void setLenientBind(Boolean lenientBind) {
    this.lenientBind = lenientBind;
  }

  public boolean hasAutoAck() {
    return autoAck != null;
  }

  public Boolean getAutoAck() {
    return autoAck;
  }

  /**
   * Configure the automatic generation of MLLP acknowledgements by the MLLP component.
   * <p>
   * If specified, this property will be used to set the autoAck parameter of the MLLP component.
   * <p>
   * If this value is null, the autoAck parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param autoAck if true; enable automatic acknowledgement generation.  If false, disable automatic acknowledgement generation.  If null, use the default from the MLLP component.
   */
  public void setAutoAck(Boolean autoAck) {
    this.autoAck = autoAck;
  }


  public boolean hasTargetType() {
    return targetType != null;
  }

  public String getTargetType() {
    return targetType;
  }

  public void setTargetType(String targetType) {
    this.targetType = targetType;
  }

  public boolean hasHl7Headers() {
    return hl7Headers != null;
  }

  public Boolean getHl7Headers() {
    return hl7Headers;
  }

  /**
   * Configure the automatic population of HL7 message headers by the MLLP component.
   *
   * If specified, this property will be used to set the hl7Headers parameter of the MLLP component.
   *
   * If this value is null, the hl7Headers parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param hl7Headers if true; enable population of HL7 message headers.  If false, disable population of HL7 message headers.  If null, use the default from the MLLP component.
   */
  public void setHl7Headers(Boolean hl7Headers) {
    this.hl7Headers = hl7Headers;
  }

  public boolean hasRequireEndOfData() {
    return requireEndOfData != null;
  }

  public Boolean getRequireEndOfData() {
    return requireEndOfData;
  }

  /**
   * Configure MLLP enveloping compliance for the MLLP component.
   *
   * If specified, this property will be used to set the requireEndOfData parameter of the MLLP component.
   *
   * If this value is null, the requireEndOfData parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param requireEndOfData if true; the END-OF-DATA byte is required.  If false, the END-OF-DATA byte is optional.  If null, use the default from the MLLP component.
   */
  public void setRequireEndOfData(Boolean requireEndOfData) {
    this.requireEndOfData = requireEndOfData;
  }

  public boolean hasValidatePayload() {
    return validatePayload != null;
  }

  public Boolean getValidatePayload() {
    return validatePayload;
  }

  /**
   * Configure the payload validation for the MLLP component.
   *
   * If specified, this property will be used to set the validatePayload parameter of the MLLP component.
   *
   * If this value is null, the validatePayload parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param validatePayload if true; enable payload validation.  If false, disable payload validations.  If null, use the default from the MLLP component.
   */
  public void setValidatePayload(Boolean validatePayload) {
    this.validatePayload = validatePayload;
  }

  public boolean hasStringPayload() {
    return stringPayload != null;
  }

  public Boolean getStringPayload() {
    return stringPayload;
  }

  /**
   * Configure the type of message body set in the exchange by the MLLP component.
   *
   * If specified, this property will be used to set the stringPayload parameter of the MLLP component.
   *
   * If this value is null, the stringPayload parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param stringPayload if true; String payloads will be set.  If false, byte-array payloads will be set.  If null, use the default from the MLLP component.
   */
  public void setStringPayload(Boolean stringPayload) {
    this.stringPayload = stringPayload;
  }

  public boolean hasCharsetName() {
    return charsetName != null && !charsetName.isEmpty();
  }

  public String getCharsetName() {
    return charsetName;
  }

  /**
   * Configure character set for the MLLP component to use when encoding/decoding payloads .
   *
   * If specified, this property will be used to set the charsetName parameter of the MLLP component.
   *
   * If this value is null, the charsetName parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param charsetName the character set name to use for payload encoding/decoding.  If null, use the default from the MLLP component.
   */
  public void setCharsetName(String charsetName) {
    this.charsetName = charsetName;
  }

  public String getMllpErrorAckType() {
    return mllpErrorAckType;
  }

  public void setMllpErrorAckType(String mllpErrorAckType) {
    this.mllpErrorAckType = mllpErrorAckType;
  }

  /**
   * verify the configuration of all required components for the routebuilder.
   * @throws InvalidConfigurationException when a required component is not set.
   */
  void verifyConfiguration() throws RuntimeException {
    if (!hasListenAddress()) {
      throw new IllegalStateException("MLLP Listening address must be specified");
    }

    if (!hasSourceComponent()) {
      throw new IllegalStateException("Source component must be specified");
    }

    if (!hasTargetComponent()) {
      throw new IllegalStateException("Target component must be specified");
    }

    if (!hasMessageDestinationName()) {
      throw new IllegalStateException("Message destination name must be specified");
    }

    if (!hasAcknowledgmentDestinationName()) {
      log.warn("Acknowledgement destination name not specified - acknowledgements will not be persisted");
    }

    // Populate some default/derived values
    if (routeId == null) {
      routeId = "mllp-receiver-" + listenAddress;
    }

    if (!hasAuditEnrichmentProcessor()) {
      InboundAuditEnrichmentProcessor auditingPreProcessor = new InboundAuditEnrichmentProcessor();
      if (hasContainerName()) {
        auditingPreProcessor.setContainerName(containerName);
      }
      if (hasMessageDestinationName()) {
        auditingPreProcessor.setDestinationName(targetDestinationName);
      }
      auditEnrichmentProcessor = auditingPreProcessor;
    }

    if (!hasAuditAcknowledgementProcessor()) {
      SentMllpAcknowledgmentAuditProcessor tmpAcknowledgmentAuditProcessor = new SentMllpAcknowledgmentAuditProcessor();

      auditAcknowledgementProcessor = tmpAcknowledgmentAuditProcessor;
    }

  }

}