package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import org.apache.camel.Exchange;
import org.apache.camel.Message;

/**
 * Camel Processor that adds Headers to Camel Messages to facilitate Auditing/tracking.
 */
public class InboundAuditEnrichmentProcessor extends AuditEnrichmentProcessorSupport {
  public static final String HEADER_NAME_PREFIX = "Receiving";

  public static final String CONTAINER_NAME_HEADER = HEADER_NAME_PREFIX + CONTAINER_NAME_HEADER_SUFFIX;
  public static final String TIMESTAMP_HEADER = HEADER_NAME_PREFIX + TIMESTAMP_HEADER_SUFFIX;
  public static final String CAMEL_ID_HEADER = HEADER_NAME_PREFIX + CAMEL_ID_HEADER_SUFFIX;
  public static final String ROUTE_ID_HEADER = HEADER_NAME_PREFIX + ROUTE_ID_HEADER_SUFFIX;
  public static final String DESTINATION_NAME_HEADER = HEADER_NAME_PREFIX + DESTINATION_NAME_HEADER_SUFFIX;

  String destinationName;

  @Override
  public String getHeaderNamePrefix() {
    return HEADER_NAME_PREFIX;
  }

  @Override
  public String getContainerNameHeader() {
    return CONTAINER_NAME_HEADER;
  }

  @Override
  public String getTimestampHeader() {
    return TIMESTAMP_HEADER;
  }

  @Override
  public String getCamelIdHeader() {
    return CAMEL_ID_HEADER;
  }

  @Override
  public String getRouteIdHeader() {
    return ROUTE_ID_HEADER;
  }

  /**
   * Adds the auditing headers to the Camel Message in the Camel Exchange.
   *
   * NOTE:  If the Exchange contains both an inbound and outbound message, the headers are only added to the outbound Message.
   *
   * @param exchange the Exchange to add headers
   */
  @Override
  public void process(Exchange exchange) {
    Message message = addCommonHeaders(exchange);

    if (hasDestinationName()) {
      message.setHeader(DESTINATION_NAME_HEADER, destinationName);
    }
  }

  /**
   * Determine if a "SendingDestinationName" header has been configured.
   *
   * @return true if the header has been configured; false otherwise
   */
  public boolean hasDestinationName() {
    return destinationName != null && !destinationName.isEmpty();
  }

  /**
   * Get the value which will be used for the "SendingDestinationName" header.
   *
   * @return the value which will be used for the header
   */
  public String getDestinationName() {
    return destinationName;
  }

  /**
   * Set the value which to use for the "SendingDestinationName" header.
   *
   * @param destinationName value of the header
   */
  public void setDestinationName(String destinationName) {
    this.destinationName = destinationName;
  }

}
