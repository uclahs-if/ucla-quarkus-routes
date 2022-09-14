package edu.ucla.mednet.iss.it.quarkus.examples.routebuilder;


import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.StringJoiner;

import org.apache.camel.LoggingLevel;
import org.apache.camel.Predicate;
import org.apache.camel.Processor;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mllp.MllpAcknowledgementException;
import org.apache.camel.component.mllp.MllpApplicationErrorAcknowledgementException;
import org.apache.camel.component.mllp.MllpApplicationRejectAcknowledgementException;
import org.apache.camel.model.OnCompletionDefinition;
import org.apache.camel.model.OnExceptionDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.ucla.mednet.iss.it.quarkus.examples.utils.FailureAuditProcessor;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.Hl7AuditMessageProcessor;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.InvalidConfigurationException;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.InvocationCounterPredicate;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.LoggingRedeliveryProcessor;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.OutboundAuditEnrichmentProcessor;
import edu.ucla.mednet.iss.it.quarkus.examples.utils.ReceivedMllpAcknowledgementProcessor;

/**
 * A Camel RouteBuilder that implements a JMS to MLLP route.
 *
 * The default flow of the route is:
 *   - Receive a message for ActiveMQ (other JMS servers may work)
 *   - Call the {@link OutboundAuditEnrichmentProcessor} payload preprocessor
 *   - Deliver the message to an MLLP destination
 *   - Call the {@link ReceivedMllpAcknowledgementProcessor} audit acknowledgement processor
 *   - Deliver the exchange to the audit acknowledgement destination
 *
 * If the route cannot connect to the MLLP endpoint, it will retry forever, logging every 5-th attempt.
 *
 * If an exchange fails, the route will deliver a failure auditing message to the failure destination and shutdown.
 *
 * Default MLLP Application Error Acknowledgment flow:
 *   - attempt redelivery 4 times
 *   - if redelivery succeeds
 *      - Call the {@link ReceivedMllpAcknowledgementProcessor} audit acknowledgement processor
 *      - deliver the exchange to the audit acknowledgement destination
 *   - if redelivery fails
 *      - Call the {@link ReceivedMllpAcknowledgementProcessor} audit acknowledgement processor
 *      - deliver the exchange to the audit acknowledgement error destination
 *
 * Default MLLP Application Reject Acknowledgment flow:
 *   - Call the {@link ReceivedMllpAcknowledgementProcessor} audit acknowledgement processor
 *   - deliver the exchange to the audit acknowledgement reject destination
 *   - shutdown the route
 *
 * The route can also be configured to attempt redelivery of when MLLP Application Reject Acknowledgments are received by setting the application reject retry count to a value greater than zero.
 * When configured, the MLLP Application Reject Acknowledgment flow changes to:
 *   - attempt redelivery 4 times
 *   - if redelivery succeeds
 *      - Call the {@link ReceivedMllpAcknowledgementProcessor} audit acknowledgement processor
 *      - deliver the exchange to the audit acknowledgement destination
 *   - if redelivery fails
 *      - Call the {@link ReceivedMllpAcknowledgementProcessor} audit acknowledgement processor
 *      - deliver the exchange to the audit acknowledgement failed destination
 *      - call the {@link InvocationCounterPredicate} application reject shutdown predicate
 *        - if the application reject shutdown predicate matches, shutdown the route

 * The configuration options available are:
 *   - the default payload preprocessor can be replaced
 *   - a delivery processor can be configured
 *   - the default audit acknowledgement processor can be replaced
 *   - the audit acknowledgement component and destination can be changed.  If one is not present, acknowledgement auditing will be disabled and the audit acknowledgement processor will be ignored
 *   - the initial and maximum redelivery delays and the redelivery backoff multiplier can be customized
 *   - the default redelivery processor can be replaced, or the route can be configured with no redelivery processor at all, in which case Camel will log every redelivery attempt
 *   - the default failure audit processor can be replaced
 *   - the route can be configured to continue running after an exchange fails
 *   - both the failure audit component and destination can be changed.  If one is not present, failure auditing will be disabled and the failure audit processor will be ignored
 */
public class JmsToMllpRouteBuilder extends RouteBuilder {
  static final String SHUTDOWN_URI = "controlbus://route?action=stop&async=true&routeId=current";
  static final String DEFAULT_AUDIT_DESTINATION_NAME = "audit-out";
  static final String DEFAULT_FAILURE_DESTINATION_NAME = "audit-failure";

  static final String DEFAULT_TARGET_COMPONENT = "mllp";
  static final String DEFAULT_APPLICATION_ACCEPT_ACKNOWLEDGEMENT_DESTINATION_NAME = "audit-ack-aa";
  static final String DEFAULT_APPLICATION_REJECT_ACKNOWLEDGEMENT_DESTINATION_NAME = "audit-ack-ar";
  static final String DEFAULT_APPLICATION_ERROR_ACKNOWLEDGEMENT_DESTINATION_NAME = "audit-ack-ae";

  private Logger log = LoggerFactory.getLogger(getClass());

  /*
    Standard route configuration
   */
  String containerName = "Not Set";
  String routeId;

  /*
    Unexpected Failure Options
   */
  boolean shutdownOnFailure = false;
  String failureComponent;
  String failureDestinationName = DEFAULT_FAILURE_DESTINATION_NAME;
  boolean customFailureProcessor;
  Processor failureProcessor;

  /*
    General Route redelivery policy configuration
   */
  boolean customRedeliveryProcessor;
  long initialRedeliveryDelay = 1000;
  long maximumRedeliveryDelay = 60000;
  double redeliveryBackOffMultiplier = 2.0;
  int logRedeliveryAttemptInterval = 5;
  Processor redeliveryProcessor;

  /*
    Source configuration
   */
  String sourceComponent;
  String sourceDestinationName;
  Boolean sourceShared = true;
  String  sourceSubscriptionId;
  Boolean sourceDurable = true;
  String sourceType = "topic";

  /*
     Audit configuration
   */
  String auditComponent;

  /*
    Target configuration
   */
  String targetComponent = DEFAULT_TARGET_COMPONENT;
  String targetAddress;
  Integer connectTimeout;
  Integer receiveTimeout; // ~Acknowledgement Timeout, Default is 10000
  Integer readTimeout;
  Integer idleTimeout;
  Boolean keepAlive;
  Boolean tcpNoDelay;
  Integer receiveBufferSize;
  Integer sendBufferSize;
  Boolean bufferWrites;
  Boolean requireEndOfData;
  Boolean validatePayload;
  String charsetName;
  boolean customPayloadPreProcessor;
  Boolean lazyStartProducer = true;

  /*
    Processing configuration
   */
  Processor payloadPreProcessor;
  Processor deliveryProcessor;
  boolean customAuditAcknowledgementProcessor;
  Processor auditAcknowledgementProcessor;
  String applicationAcceptAcknowledgementDestinationName = DEFAULT_APPLICATION_ACCEPT_ACKNOWLEDGEMENT_DESTINATION_NAME;
  boolean customApplicationRejectShutdownPredicate;
  Predicate applicationRejectShutdownPredicate;

  /*
    General NACK Handling options
   */
  int negativeAcknowledgementRetryDelay = 5000;

  /*
    AE Handling options
   */
  int applicationErrorAcknowledgementRetryCount = 4;
  String applicationErrorAcknowledgementDestinationName = DEFAULT_APPLICATION_ERROR_ACKNOWLEDGEMENT_DESTINATION_NAME;

  /*
    AR Handling options
   */
  int applicationRejectAcknowledgementRetryCount = 0;
  int maxApplicationRejectAcknowledgementsBeforeShutdown = 0;
  int applicationRejectAcknowledgementCountResetInterval = 60000;
  String applicationRejectAcknowledgementDestinationName = DEFAULT_APPLICATION_REJECT_ACKNOWLEDGEMENT_DESTINATION_NAME;

  /*
    HL7 Audit Processor
   */
  Hl7AuditMessageProcessor hl7AuditMessageProcessor;
  String auditDestinationName = DEFAULT_AUDIT_DESTINATION_NAME;
  boolean logTargetMessage = false;

  /**
   * Default constructor for the RouteBuilder.
   *
   * Initialize the default post-processor.
   */
  public JmsToMllpRouteBuilder() {
    hl7AuditMessageProcessor = new Hl7AuditMessageProcessor();
  }

  @Override
  public void configure() throws Exception {
    final String ackWarningFormat =
        "The Application {} Acknowledgement Destination Name is not specified"
            + " - these acknowledgements will not be audited";

    verifyConfiguration();

    errorHandler(defaultErrorHandler().allowRedeliveryWhileStopping(false));

    // Set up the AE exception clause
    OnExceptionDefinition aeExceptionDefinition =
        onException(MllpApplicationErrorAcknowledgementException.class).id(routeId + ": Application Error Acknowledgement Handler")
            .handled(true)
            .maximumRedeliveries(applicationErrorAcknowledgementRetryCount)
            .redeliveryDelay(negativeAcknowledgementRetryDelay)
            .logRetryAttempted(true)
            .logContinued(true)
            .logExhausted(true)
            .retryAttemptedLogLevel(LoggingLevel.INFO)
            .retriesExhaustedLogLevel(LoggingLevel.WARN);
    if (hasApplicationErrorAcknowledgementDestinationName()) {
      if (hasAuditAcknowledgementProcessor()) {
        aeExceptionDefinition = aeExceptionDefinition.process(auditAcknowledgementProcessor).id(routeId + ": Prepare Application Error Acknowledgement for auditing");
      }
      aeExceptionDefinition.toF("%s:topic:%s", getAuditComponent(), applicationErrorAcknowledgementDestinationName).id(routeId + ": Audit Application Error Acknowledgement");

    } else {
      log.warn(ackWarningFormat, "Error");
    }

    // Set up the AR exception clause
    OnExceptionDefinition arExceptionDefinition =
        onException(MllpApplicationRejectAcknowledgementException.class).id(routeId + ": Application Reject Acknowledgement Handler");
    if (hasApplicationRejectShutdownPredicate()) {
      arExceptionDefinition = arExceptionDefinition.handled(applicationRejectShutdownPredicate);
    } else {
      arExceptionDefinition = arExceptionDefinition.handled(false);
    }
    arExceptionDefinition = arExceptionDefinition
        .maximumRedeliveries(applicationRejectAcknowledgementRetryCount)
        .redeliveryDelay(negativeAcknowledgementRetryDelay)
        .logRetryAttempted(true)
        .logContinued(true)
        .logExhausted(true)
        .retryAttemptedLogLevel(LoggingLevel.WARN)
        .retriesExhaustedLogLevel(LoggingLevel.WARN);
    if (hasApplicationRejectAcknowledgementDestinationName()) {
      if (hasAuditAcknowledgementProcessor()) {
        arExceptionDefinition = arExceptionDefinition.process(auditAcknowledgementProcessor)
            .id(routeId + ": Prepare Application Reject Acknowledgement for auditing");
      }
      arExceptionDefinition = arExceptionDefinition.toF("%s:topic:%s", getAuditComponent(), applicationRejectAcknowledgementDestinationName).id(routeId + ": Audit Application Reject Acknowledgement");

    } else {
      log.warn(ackWarningFormat, "Reject");
    }
    if (hasApplicationRejectShutdownPredicate()) {
      arExceptionDefinition
          .filter(exchangeProperty(InvocationCounterPredicate.DEFAULT_INVOCATION_COUNTER_MATCHED_PROPERTY).isNotEqualTo(true))
          .log(LoggingLevel.ERROR,
              "Application Reject Acknowledgement Handler: ${header[CamelMllpAcknowledgement]} - stopping context=\"${camelId}\" route=\"${routeId}\"").id(routeId + ": Log AR Shutdown")
          .toF(SHUTDOWN_URI, false).id(routeId + ": Stop Route after AR");
    }

    // Setup the 'Retry-forever' exception clause
    @SuppressWarnings("unchecked")
    OnExceptionDefinition retryForeverRedeliveryHandler =
        onException(ConnectException.class, SocketException.class, SocketTimeoutException.class, MllpAcknowledgementException.class).id(routeId + ": Retry Forever Handler")
            .handled(false)
            .maximumRedeliveries(-1)
            .redeliveryDelay(getInitialRedeliveryDelay())
            .maximumRedeliveryDelay(getMaximumRedeliveryDelay())
            .useExponentialBackOff()
            .backOffMultiplier(getRedeliveryBackOffMultiplier())
            .logRetryAttempted(!hasRedeliveryProcessor())
            .logContinued(true)
            .logExhausted(true)
            .retryAttemptedLogLevel(LoggingLevel.WARN)
            .retriesExhaustedLogLevel(LoggingLevel.ERROR);
    if (hasRedeliveryProcessor()) {
      retryForeverRedeliveryHandler
          .onRedelivery(getRedeliveryProcessor());
    }

    // Setup the exchange failure completion clause
    OnCompletionDefinition onFailure = onCompletion().id(routeId + ": onFailureOnly Handler")
        .onFailureOnly()
        .log(LoggingLevel.ERROR, "onFailureOnly Handler: stopping context=\"${camelId}\" route=\"${routeId}\"").id(routeId + ": Log Failure Shutdown");
    if (hasFailureComponent() && hasFailureDestinationName()) {
      if (hasFailureProcessor()) {
        onFailure.process(getFailureProcessor()).id(routeId + ": Prepare for audit")
            .log(LoggingLevel.TRACE, "Prepare for audit");
      }
      onFailure.toF("%s:topic:%s", getFailureComponent(), getFailureDestinationName()).id(routeId + ": Audit failure")
          .log(LoggingLevel.TRACE, "Audit failure");
    }
    if (isShutdownOnFailure()) {
      onFailure.log(LoggingLevel.TRACE, "Stop Route on failure");
      onFailure.toF(SHUTDOWN_URI, true).id(routeId + ": Stop Route on failure");
    }

    // Set up the route
    ProcessorDefinition<?> fromDefinition = fromF("%s:%s:%s%s", getSourceComponent(), sourceType, sourceDestinationName, getSourceComponentOptions())
        .routeId(routeId);

    if (hasPayloadPreProcessor()) {
      fromDefinition = fromDefinition.process(payloadPreProcessor).id(routeId + ": Payload Pre-Processor")
          .log(LoggingLevel.TRACE, "Payload Pre-Processor");
    }

    if (hasDeliveryProcessor()) {
      fromDefinition = fromDefinition.process(deliveryProcessor)
          .id(routeId + ": Prepare for delivery")
          .log(LoggingLevel.TRACE, "Prepare for delivery");
    }

    fromDefinition = fromDefinition.toF("mllp://%s%s", targetAddress, getTargetComponentOptions()).id(routeId + ": Send Message");


    // Setup Audit Processor

    if (hl7AuditMessageProcessor != null) {
      fromDefinition = fromDefinition.bean(hl7AuditMessageProcessor)
          .log(LoggingLevel.TRACE, "hl7AuditMessageProcessor");
    }
    // Audit the message (AUDIT-OUT)

    fromDefinition = fromDefinition.toF("%s:topic:%s?exchangePattern=InOnly&transacted=true", getAuditComponent(), auditDestinationName)
        .id(routeId + ": Audit Message")
        .log(LoggingLevel.TRACE, "hl7AuditMessageProcessor");;

    // Audit the ack (AUDIT-ACK-AA)
    if (hasAuditAcknowledgementProcessor()) {
      fromDefinition = fromDefinition.process(auditAcknowledgementProcessor)
          .id(routeId + ": Prepare Application Accept Acknowledgement for auditing")
          .log(LoggingLevel.TRACE, "Prepare Application Accept Acknowledgement for auditing");
    }

    fromDefinition = fromDefinition.toF("%s:topic:%s", getAuditComponent(), applicationAcceptAcknowledgementDestinationName)
        .id(routeId + ": Audit Application Accept Acknowledgement")
        .log(LoggingLevel.TRACE, "Audit Application Accept Acknowledgement");



    // LOG THE MESSAGE to the console
    fromDefinition.setBody(body().regexReplaceAll("\\r", System.lineSeparator()))
        .log(LoggingLevel.INFO, "MLLP OUT ${bodyOneLine}");

  }

  // TODO:  Put getters/setters in the same order as properties above
  public boolean hasContainerName() {
    return containerName != null && !containerName.isEmpty();
  }

  public String getContainerName() {
    return containerName;
  }

  /**
   * Configure the value of the container name.
   *
   * This property is use when configuring the default {@link OutboundAuditEnrichmentProcessor}.  If this value is null or empty, the containerName property of the
   * default {@link OutboundAuditEnrichmentProcessor} will not be configured by the RouteBuilder.
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
   * If this value is null or empty, the Camel Route ID will be derived from the target MLLP address, and the format of the ID is:
   *      mllp-sender-{targetAddress}
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
   * The default value of this property is 'true'.
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
   * If this property is null or empty, the SJMS source component will be used.
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

  public boolean hasFailureProcessor() {
    return failureProcessor != null;
  }

  public Processor getFailureProcessor() {
    return failureProcessor;
  }

  /**
   * Generate the SJMS2 source component URI option String for the configured options.
   *
   * @return the SJMS2 source component URI option String
   */
  public String getSourceComponentOptions() {
    StringJoiner componentOptions = new StringJoiner("&", "?", "");
    componentOptions.setEmptyValue("");
    componentOptions.add("transacted=true");
    if (hasSourceShared()) {
      componentOptions.add("shared=" + sourceShared);
    }
    if (hasSourceSubscriptionId()) {
      componentOptions.add("subscriptionId=" + getSourceDestinationName() + "-" + getSourceSubscriptionId());
    }
    if (hasSourceDurable()) {
      componentOptions.add("durable=" + sourceDurable);
    }

    return componentOptions.toString();
  }

  public boolean hasSourceShared() {
    return sourceShared != null;
  }

  public Boolean getSourceShared() {
    return sourceShared;
  }

  public void setSourceShared(Boolean sourceShared) {
    this.sourceShared = sourceShared;
  }

  public boolean hasSourceSubscriptionId() {
    return sourceSubscriptionId != null;
  }

  public String getSourceSubscriptionId() {
    return sourceSubscriptionId;
  }

  public void setSourceSubscriptionId(String sourceSubscriptionId) {
    this.sourceSubscriptionId = sourceSubscriptionId;
  }

  public boolean hasSourceDurable() {
    return sourceDurable != null;
  }

  public void setSourceDurable(Boolean sourceDurable) {
    this.sourceDurable = sourceDurable;
  }

  public boolean hasSourceType() {
    return sourceType != null;
  }

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }



  /**
   * Configure the Camel Processor used to prepare the exchange for delivery to the failure destination.
   *
   * By default, a {@link FailureAuditProcessor} is used.
   *
   * If this value is null, the exchange will be delivered to the failure destination without pre-processing.
   *
   * @param failureProcessor the failure processor for preparing exchanges for deliver to the failure destination.
   */
  public void setFailureProcessor(Processor failureProcessor) {
    customFailureProcessor = true;
    this.failureProcessor = failureProcessor;
  }


  public boolean hasTargetAddress() {
    return targetAddress != null && !targetAddress.isEmpty();
  }

  public String getTargetAddress() {
    return targetAddress;
  }

  /**
   * Configure the target network address of the MLLP component.
   *
   * @param targetAddress the MLLP target network address
   */
  public void setTargetAddress(String targetAddress) {
    this.targetAddress = targetAddress;
  }

  /**
   * Configure the target port (0.0.0.0 will be used for the host) of the MLLP component.
   *
   * @param port the MLLP target port
   */
  public void setTargetAddress(int port) {
    setTargetAddress("localhost", port);
  }

  /**
   * Configure the target network address of the MLLP component.
   *
   * @param host the MLLP target host
   * @param port the MLLP target port
   */
  public void setTargetAddress(String host, int port) {
    this.targetAddress = String.format("%s:%d", host, port);
  }

  public boolean hasSourceComponent() {
    return sourceComponent != null && !sourceComponent.isEmpty();
  }

  public String getSourceComponent() {
    return sourceComponent;
  }

  /**
   * Configure the name of the SJMS component in the Camel registry for source messages.
   *
   * @param auditComponent the name of the audit component in the Camel registry
   */
  public void setAuditComponent(String auditComponent) {
    this.auditComponent = auditComponent;
  }

  public boolean hasAuditComponent() {
    return (auditComponent != null && !auditComponent.isEmpty()) || hasSourceComponent();
  }

  public String getAuditComponent() {
    return (auditComponent != null && !auditComponent.isEmpty()) ? auditComponent : getSourceComponent();
  }

  /**
   * Configure the name of the SJMS component in the Camel registry for source messages.
   *
   * @param sourceComponent the name of the source component in the Camel registry
   */
  public void setSourceComponent(String sourceComponent) {
    this.sourceComponent = sourceComponent;
  }

  public boolean hasTargetComponent() {
    return targetComponent != null && !targetComponent.isEmpty();
  }

  public String getTargetComponent() {
    return targetComponent;
  }

  /**
   * Configure the name of the MLLP component in the Camel registry for target messages.
   *
   * @param targetComponent the name of the target component in the Camel registry
   */
  public void setTargetComponent(String targetComponent) {
    this.targetComponent = targetComponent;
  }

  public boolean hasSourceDestinationName() {
    return sourceDestinationName != null && !sourceDestinationName.isEmpty();
  }

  public String getSourceDestinationName() {
    return sourceDestinationName;
  }

  /**
   * Configure the name of the JMS destination to use as a message source.
   *
   * @param sourceDestinationName the JMS destination name for source messages
   */
  public void setSourceDestinationName(String sourceDestinationName) {
    this.sourceDestinationName = sourceDestinationName;
  }

  public boolean hasApplicationAcceptAcknowledgementDestinationName() {
    return applicationAcceptAcknowledgementDestinationName != null && !applicationAcceptAcknowledgementDestinationName.isEmpty();
  }

  public String getApplicationAcceptAcknowledgementDestinationName() {
    return applicationAcceptAcknowledgementDestinationName;
  }

  /**
   * Configure the JMS destination name for MLLP Application Accept Acknowledgements.
   *
   * The default value of this property is audit.ack.aa
   *
   * @param applicationAcceptAcknowledgementDestinationName the JMS destination name
   */
  public void setApplicationAcceptAcknowledgementDestinationName(String applicationAcceptAcknowledgementDestinationName) {
    this.applicationAcceptAcknowledgementDestinationName = applicationAcceptAcknowledgementDestinationName;
  }

  public boolean hasApplicationErrorAcknowledgementDestinationName() {
    return applicationErrorAcknowledgementDestinationName != null && !applicationErrorAcknowledgementDestinationName.isEmpty();
  }

  public String getApplicationErrorAcknowledgementDestinationName() {
    return applicationErrorAcknowledgementDestinationName;
  }

  /**
   * Configure the JMS destination name for MLLP Application Error Acknowledgements.
   *
   * NOTE: The acknowledgment will be sent here only if the redelivery process fails (an event will not be sent to this destination for every AE received)
   *
   * The default value of this property is audit.ack.ae
   *
   * @param applicationErrorAcknowledgementDestinationName the JMS destination name
   */
  public void setApplicationErrorAcknowledgementDestinationName(String applicationErrorAcknowledgementDestinationName) {
    this.applicationErrorAcknowledgementDestinationName = applicationErrorAcknowledgementDestinationName;
  }

  public boolean hasApplicationRejectAcknowledgementDestinationName() {
    return applicationRejectAcknowledgementDestinationName != null && !applicationRejectAcknowledgementDestinationName.isEmpty();
  }

  public String getApplicationRejectAcknowledgementDestinationName() {
    return applicationRejectAcknowledgementDestinationName;
  }

  /**
   * Configure the JMS destination name for MLLP Application Reject Acknowledgements.
   *
   * NOTE: The acknowledgment will be sent here only if the redelivery process is configured and fails (an event will not be sent to this destination for every AR received)
   *
   * The default value of this property is audit.ack.ar
   *
   * @param applicationRejectAcknowledgementDestinationName the JMS destination name
   */
  public void setApplicationRejectAcknowledgementDestinationName(String applicationRejectAcknowledgementDestinationName) {
    this.applicationRejectAcknowledgementDestinationName = applicationRejectAcknowledgementDestinationName;
  }

  public long getInitialRedeliveryDelay() {
    return initialRedeliveryDelay;
  }

  /**
   * Configure the amount of time to wait before the initial attempt to redeliver an exchange.
   *
   * The default value of this property is 1-sec (1000 milliseconds).
   *
   * @param initialRedeliveryDelay the initial delay (in milliseconds) before attempting to redeliver an exchange.
   */
  public void setInitialRedeliveryDelay(long initialRedeliveryDelay) {
    this.initialRedeliveryDelay = initialRedeliveryDelay;
  }

  public long getMaximumRedeliveryDelay() {
    return maximumRedeliveryDelay;
  }

  /**
   * Configure the maximum amount of time to wait between attempts to redeliver an exchange.
   *
   * The default value of this property is 60-sec (60000 milliseconds).
   *
   * @param maximumRedeliveryDelay the maximum delay (in milliseconds) between attempts to redeliver an exchange.
   */
  public void setMaximumRedeliveryDelay(long maximumRedeliveryDelay) {
    this.maximumRedeliveryDelay = maximumRedeliveryDelay;
  }

  public double getRedeliveryBackOffMultiplier() {
    return redeliveryBackOffMultiplier;
  }

  /**
   * Configure the multiplier to apply when determining the delay between redelivery attempts.
   *
   * The default value of this property is 2.0.
   *
   * @param redeliveryBackOffMultiplier the backoff multiplier
   */
  public void setRedeliveryBackOffMultiplier(double redeliveryBackOffMultiplier) {
    this.redeliveryBackOffMultiplier = redeliveryBackOffMultiplier;
  }

  public int getLogRedeliveryAttemptInterval() {
    return logRedeliveryAttemptInterval;
  }

  /**
   * Configure the logRedeliveryAttemptInterval property for the default redelivery processor.
   *
   * If a custom redelivery processor is not configured, the RouteBuilder will create a {@link LoggingRedeliveryProcessor} and set it's logRedeliveryAttemptInterval using this value.
   *
   * If a custom redelivery processor is configured, this value is ignored.
   *
   * The default value of this property is 5.
   *
   * @param logRedeliveryAttemptInterval the logRedeliveryAttemptInterval property for the default {@link LoggingRedeliveryProcessor}
   */
  public void setLogRedeliveryAttemptInterval(int logRedeliveryAttemptInterval) {
    this.logRedeliveryAttemptInterval = logRedeliveryAttemptInterval;
  }

  public int getNegativeAcknowledgementRetryDelay() {
    return negativeAcknowledgementRetryDelay;
  }

  /**
   * Configure the amount of time to wait for the initial redelivery of a message is attempted after an negative acknowledgement is received.
   *
   * The default value of this property is 5-sec (5000 milliseconds)
   *
   * @param negativeAcknowledgementRetryDelay the initial redelivery delay (in milliseconds)
   */
  public void setNegativeAcknowledgementRetryDelay(int negativeAcknowledgementRetryDelay) {
    this.negativeAcknowledgementRetryDelay = negativeAcknowledgementRetryDelay;
  }

  public int getApplicationErrorAcknowledgementRetryCount() {
    return applicationErrorAcknowledgementRetryCount;
  }

  /**
   * Configure the MLLP Application Error redelivery.
   *
   * If this value is greater than zero, the route will attempt to redeliver the message when an MLLP Application Error Acknowledgement is received.  If this property
   * is null or less than 1, redelivery of messages will not be attempted when an MLLP Application Error Acknowledgment is received - they will immediately be delivered to the
   * application error acknowledgement destination.
   *
   * The default value of this property is 4.
   *
   * @param applicationErrorAcknowledgementRetryCount the number of redelivery attempts when MLLP Application Error Acknowledgements are received
   */
  public void setApplicationErrorAcknowledgementRetryCount(int applicationErrorAcknowledgementRetryCount) {
    this.applicationErrorAcknowledgementRetryCount = applicationErrorAcknowledgementRetryCount;
  }

  public int getApplicationRejectAcknowledgementRetryCount() {
    return applicationRejectAcknowledgementRetryCount;
  }

  /**
   * Configure the MLLP Application Reject redelivery.
   *
   * If this value is greater than zero, the route will attempt to redeliver the message when an MLLP Application Reject Acknowledgement is received.  If this property
   * is null or less than 1, redelivery of messages will not be attempted when an MLLP Application Reject Acknowledgment is received - they will immediately be delivered to the
   * application reject acknowledgement destination.
   *
   * The default value of this property is 0 (disabling redelivery MLLP Application Reject Acknowledgements are received.
   *
   * @param applicationRejectAcknowledgementRetryCount the number of redelivery attempts when MLLP Application Reject Acknowledgements are received
   */
  public void setApplicationRejectAcknowledgementRetryCount(int applicationRejectAcknowledgementRetryCount) {
    this.applicationRejectAcknowledgementRetryCount = applicationRejectAcknowledgementRetryCount;
  }

  public int getMaxApplicationRejectAcknowledgementsBeforeShutdown() {
    return maxApplicationRejectAcknowledgementsBeforeShutdown;
  }

  /**
   * Configure the maximum number of MLLP Application Reject Acknowledgements received within the reset interval before the route will shutdown.
   *
   * This paramenter is used to configure the default {@link InvocationCounterPredicate} application reject shutdown predicate.  If a custom
   * application reject shutdown predicate is configured, this property will be ignored.
   *
   * @param maxApplicationRejectAcknowledgementsBeforeShutdown the maxInvocations used when configuring the default the application reject shutdown predicate
   */
  public void setMaxApplicationRejectAcknowledgementsBeforeShutdown(int maxApplicationRejectAcknowledgementsBeforeShutdown) {
    this.maxApplicationRejectAcknowledgementsBeforeShutdown = (maxApplicationRejectAcknowledgementsBeforeShutdown < -1) ? -1 : maxApplicationRejectAcknowledgementsBeforeShutdown;
  }

  public int getApplicationRejectAcknowledgementCountResetInterval() {
    return applicationRejectAcknowledgementCountResetInterval;
  }

  /**
   * Configure the interval for resetting the counter for MLLP Application Reject Acknowledgements.
   *
   * This paramenter is used to configure the default {@link InvocationCounterPredicate} application reject shutdown predicate.  If a custom
   * application reject shutdown predicate is configured, this property will be ignored.
   *
   * @param applicationRejectAcknowledgementCountResetInterval the interval used when configuring the default the application reject shutdown predicate
   */
  public void setApplicationRejectAcknowledgementCountResetInterval(int applicationRejectAcknowledgementCountResetInterval) {
    this.applicationRejectAcknowledgementCountResetInterval = applicationRejectAcknowledgementCountResetInterval;
  }

  public boolean hasPayloadPreProcessor() {
    return payloadPreProcessor != null;
  }

  public Processor getPayloadPreProcessor() {
    return payloadPreProcessor;
  }

  /**
   * Configure the Camel Processor that will first process the exchange.
   *
   * By default, the RouteBuilder will create and use an {@link OutboundAuditEnrichmentProcessor}.
   *
   * If this property is explicitly set to null, no payload preprocessing will be performed.
   *
   * @param payloadPreProcessor the payload pre-processor
   */
  public void setPayloadPreProcessor(Processor payloadPreProcessor) {
    customPayloadPreProcessor = true;
    this.payloadPreProcessor = payloadPreProcessor;
  }

  public boolean hasDeliveryProcessor() {
    return deliveryProcessor != null;
  }

  public Processor getDeliveryProcessor() {
    return deliveryProcessor;
  }

  /**
   * Configure a Camel Processor that to prepare the exchange for delivery to the MLLP target.
   *
   * By default, the RouteBuilder does not use a delivery processor.
   *
   * @param deliveryProcessor the processor to call before delivering the exchange to the MLLP endpoint
   */
  public void setDeliveryProcessor(Processor deliveryProcessor) {
    this.deliveryProcessor = deliveryProcessor;
  }

  public boolean hasAuditAcknowledgementProcessor() {
    return auditAcknowledgementProcessor != null;
  }

  public Processor getAuditAcknowledgementProcessor() {
    return auditAcknowledgementProcessor;
  }

  /**
   * Configure the Camel Processor that will prepare the exchange for auditing a successful delivery.
   *
   * If this property is null, the RouteBuilder will create and configure a {@link ReceivedMllpAcknowledgementProcessor}.
   *
   * If this property is explicitly set to null, the exchange will be sent to the auditing endpoint as-is.
   *
   * @param auditAcknowledgementProcessor the processor to call before delivering the exchange to the auditing endpoint
   */
  public void setAuditAcknowledgementProcessor(Processor auditAcknowledgementProcessor) {
    customAuditAcknowledgementProcessor = true;
    this.auditAcknowledgementProcessor = auditAcknowledgementProcessor;
  }

  public boolean hasRedeliveryProcessor() {
    return redeliveryProcessor != null;
  }

  public Processor getRedeliveryProcessor() {
    return redeliveryProcessor;
  }

  /**
   * Configure a processor to call before attempting to redeliver exchanges.
   *
   * By default, the RouteBuilder creates a {@link LoggingRedeliveryProcessor}.
   *
   * If this property is explicitly set to null, no redelivery processing will be performed..
   *
   * @param redeliveryProcessor the Camel processor to call before attempting redelivery of an exchange
   */
  public void setRedeliveryProcessor(Processor redeliveryProcessor) {
    customRedeliveryProcessor = true;
    this.redeliveryProcessor = redeliveryProcessor;
  }

  public boolean hasApplicationRejectShutdownPredicate() {
    return applicationRejectShutdownPredicate != null;
  }

  public Predicate getApplicationRejectShutdownPredicate() {
    return applicationRejectShutdownPredicate;
  }

  /**
   * Configure the predicate used to determine if a route should shutdown when an MLLP Application Reject Acknowledgement is received.
   *
   * @param applicationRejectShutdownPredicate the application reject shutdown predicate
   */
  public void setApplicationRejectShutdownPredicate(Predicate applicationRejectShutdownPredicate) {
    customApplicationRejectShutdownPredicate = true;
    this.applicationRejectShutdownPredicate = applicationRejectShutdownPredicate;
  }

  public boolean hasConnectTimeout() {
    return connectTimeout != null;
  }

  public Integer getConnectTimeout() {
    return connectTimeout;
  }

  /**
   * Configure the TCP connection timeout for the MLLP component.
   *
   * If specified, this property will be used to set the connectTimeout parameter of the MLLP component.
   *
   * If this value is null, the connectTimeout parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param connectTimeout the TCP connection timeout (in milliseconds).  If null, use the default from the MLLP component
   */
  public void setConnectTimeout(Integer connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public boolean hasReceiveTimeout() {
    return receiveTimeout != null;
  }

  public Integer getReceiveTimeout() {
    return receiveTimeout;
  }

  /**
   * Configure the TCP receive timeout for the MLLP component, which effectively configures the MLLP acknowledgement timeout.
   *
   * If specified, this property will be used to set the receiveTimeout parameter of the MLLP component.
   *
   * If this value is null, the receiveTimeout parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param receiveTimeout the TCP receive timeout (in milliseconds).  If null, use the default from the MLLP component.
   */
  public void setReceiveTimeout(Integer receiveTimeout) {
    this.receiveTimeout = receiveTimeout;
  }

  public boolean hasReadTimeout() {
    return readTimeout != null;
  }

  public Integer getReadTimeout() {
    return readTimeout;
  }

  /**
   * Configure the TCP read timeout for the MLLP component.
   *
   * If specified, this property will be used to set the readTimeout parameter of the MLLP component.
   *
   * If this value is null, the readTimeout parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param readTimeout the TCP receive timeout (in milliseconds).  If null, use the default from the MLLP component.
   */
  public void setReadTimeout(Integer readTimeout) {
    this.readTimeout = readTimeout;
  }

  public boolean hasIdleTimeout() {
    return idleTimeout != null;
  }

  public Integer getIdleTimeout() {
    return idleTimeout;
  }

  /**
   * Configure the idle timeout for the MLLP component.
   *
   * If specified, this property will be used to set the idleTimeout parameter of the MLLP component.
   *
   * If this value is null, the idleTimeout parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param idleTimeout the TCP receive timeout (in milliseconds).  If null, use the default from the MLLP component.
   */
  public void setIdleTimeout(Integer idleTimeout) {
    this.idleTimeout = idleTimeout;
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

  public boolean hasBufferWrites() {
    return bufferWrites != null;
  }

  public Boolean getBufferWrites() {
    return bufferWrites;
  }

  /**
   * Configure write buffering for the MLLP component.
   *
   * If specified, this property will be used to set the bufferWrites parameter of the MLLP component.
   *
   * If this value is null, the bufferWrites parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param bufferWrites if true; enable write buffering.  If false, disable write buffering.  If null, use the default from the MLLP component.
   *
   * @deprecated this property has been deprecated by the MLLP component - it's value may be ignored.
   */
  @Deprecated
  public void setBufferWrites(Boolean bufferWrites) {
    this.bufferWrites = bufferWrites;
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
   * Configure payload validation for the MLLP component.
   *
   * If specified, this property will be used to set the validatePayload parameter of the MLLP component.
   *
   * If this value is null, the validatePayload parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param validatePayload if true; enable payload validation.  If false, disable payload validation.  If null, use the default from the MLLP component.
   */
  public void setValidatePayload(Boolean validatePayload) {
    this.validatePayload = validatePayload;
  }

  public boolean hasCharsetName() {
    return charsetName != null && !charsetName.isEmpty();
  }

  public String getCharsetName() {
    return charsetName;
  }

  /**
   * Configure lazy start producer for the MLLP component.
   *
   * If specified, this property will be used to set the lazyStartProducer parameter of the MLLP component.
   *
   * If this value is null, the validatePayload parameter will not be specified in the MLLP URI and the MLLP component will use it's default value.
   *
   * @see <a href="https://github.com/apache/camel/blob/main/components/camel-mllp/src/main/docs/mllp-component.adoc"></a>
   *
   * @param lazyStartProducer if true; enable payload validation.  If false, disable payload validation.  If null, use the default from the MLLP component.
   */
  public void setLazyStartProducer(Boolean lazyStartProducer) {
    this.lazyStartProducer = validatePayload;
  }

  public boolean hasLazyStartProducer() {
    return lazyStartProducer != null;
  }

  public Boolean getLazyStartProducer() {
    return lazyStartProducer;
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

  /**
   * Determine if any options have been configured for the MLLP Target component.
   *
   * @return true of and target component options have been configured; false otherwise
   */
  public boolean hasTargetComponentOptions() {
    return hasConnectTimeout()
        || hasReceiveTimeout()
        || hasReadTimeout()
        || hasIdleTimeout()
        || hasKeepAlive()
        || hasTcpNoDelay()
        || hasReceiveBufferSize()
        || hasSendBufferSize()
        || hasBufferWrites()
        || hasRequireEndOfData()
        || hasCharsetName()
        || hasValidatePayload()
        || hasLazyStartProducer();
  }

  /**
   * Return the String containing all the URI options for the target component.
   *
   * @return the URI options string
   */
  public String getTargetComponentOptions() {
    StringJoiner componentOptions = new StringJoiner("&", "?", "");
    componentOptions.setEmptyValue("");

    if (hasConnectTimeout()) {
      componentOptions.add("acceptTimeout=" + connectTimeout);
    }
    if (hasReceiveTimeout()) {
      componentOptions.add("receiveTimeout=" + receiveTimeout);
    }
    if (hasReadTimeout()) {
      componentOptions.add("readTimeout=" + readTimeout);
    }
    if (hasIdleTimeout()) {
      componentOptions.add("idleTimeout=" + idleTimeout);
    }
    if (hasKeepAlive()) {
      componentOptions.add("keepAlive=" + keepAlive);
    }
    if (hasTcpNoDelay()) {
      componentOptions.add("tcpNoDelay=" + tcpNoDelay);
    }
    if (hasReceiveBufferSize()) {
      componentOptions.add("receiveBufferSize=" + receiveBufferSize);
    }
    if (hasSendBufferSize()) {
      componentOptions.add("sendBufferSize=" + sendBufferSize);
    }
    if (hasBufferWrites()) {
      componentOptions.add("bufferWrites=" + bufferWrites);
    }
    if (hasRequireEndOfData()) {
      componentOptions.add("requireEndOfData=" + requireEndOfData);
    }
    if (hasValidatePayload()) {
      componentOptions.add("validatePayload=" + validatePayload);
    }
    if (hasCharsetName()) {
      componentOptions.add("charsetName=" + charsetName);
    }
    if (hasLazyStartProducer()) {
      componentOptions.add("lazyStartProducer=" + lazyStartProducer);
    }

    return componentOptions.toString();
  }

  /**
   * verify the configuration of all required components for the routebuilder.
   * @throws InvalidConfigurationException when a required component is not set.
   */
  void verifyConfiguration() throws RuntimeException {
    if (!hasTargetAddress()) {
      throw new IllegalStateException("MLLP target address must be specified");
    }

    if (!hasSourceComponent()) {
      throw new IllegalStateException("Source component must be specified");
    }

    if (!hasTargetComponent()) {
      throw new IllegalStateException("Target component must be specified");
    }

    if (!hasSourceDestinationName()) {
      throw new IllegalStateException("Source destination name must be specified");
    }

    if (!hasRouteId()) {
      routeId = "mllp-sender-" + targetAddress;
    }

    if (!customFailureProcessor) {
      failureProcessor = new FailureAuditProcessor();
    }

    if (!customPayloadPreProcessor) {
      OutboundAuditEnrichmentProcessor auditingPreProcessor = new OutboundAuditEnrichmentProcessor();
      if (hasContainerName()) {
        auditingPreProcessor.setContainerName(containerName);
      }
      if (hasSourceDestinationName()) {
        auditingPreProcessor.setDestinationName(sourceDestinationName);
      }
      payloadPreProcessor = auditingPreProcessor;
    }

    if (!customAuditAcknowledgementProcessor) {
      ReceivedMllpAcknowledgementProcessor tmpAcknowledgmentAuditProcessor = new ReceivedMllpAcknowledgementProcessor();

      auditAcknowledgementProcessor = tmpAcknowledgmentAuditProcessor;
    }

    if (!customRedeliveryProcessor) {
      LoggingRedeliveryProcessor tmpRedeliveryProcessor = new LoggingRedeliveryProcessor();
      tmpRedeliveryProcessor.setLogAfterRedeliveryAttempts(logRedeliveryAttemptInterval);
      redeliveryProcessor = tmpRedeliveryProcessor;
    }

    if (!customApplicationRejectShutdownPredicate) {
      InvocationCounterPredicate shutdownPredicate = new InvocationCounterPredicate();

      shutdownPredicate.setMaxInvocations(maxApplicationRejectAcknowledgementsBeforeShutdown);
      shutdownPredicate.setInvocationCountResetInterval(applicationRejectAcknowledgementCountResetInterval);

      applicationRejectShutdownPredicate = shutdownPredicate;
    }
  }

}
