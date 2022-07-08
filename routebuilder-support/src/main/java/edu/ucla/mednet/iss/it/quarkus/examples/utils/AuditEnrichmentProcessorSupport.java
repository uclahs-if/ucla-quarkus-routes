package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base Camel processor to support adding audit headers to messages to facilitate auditing/tracing.
 *
 * This processor is intended to be used for augmenting the Camel message by adding headers and/or properties.
 * The body of the Camel message should not be altered.
 */
public abstract class AuditEnrichmentProcessorSupport implements Processor {
  private static final Logger LOG = LoggerFactory.getLogger(AuditEnrichmentProcessorSupport.class);

  public static final String CONTAINER_NAME_HEADER_SUFFIX = "ContainerName";
  public static final String TIMESTAMP_HEADER_SUFFIX = "Timestamp";
  public static final String CAMEL_ID_HEADER_SUFFIX = "CamelId";
  public static final String ROUTE_ID_HEADER_SUFFIX = "RouteId";
  public static final String AUDIT_BREADCRUMB_ID_HEADER = "breadcrumbId";

  public static final String DESTINATION_NAME_HEADER_SUFFIX = "DestinationName";
  public static final String SOURCE_DESTINATION_NAME_HEADER_SUFFIX = "Source" + DESTINATION_NAME_HEADER_SUFFIX;
  public static final String TARGET_DESTINATION_NAME_HEADER_SUFFIX = "Target" + DESTINATION_NAME_HEADER_SUFFIX;

  String containerName = System.getProperty("karaf.name");
  String breadcrumbId;

  /**
   * Get the prefix used for the Camel header names.
   *
   * @return the prefix for the Camel header names.
   */
  public abstract String getHeaderNamePrefix();


  /**
   * Get the header name for the Container Name value.
   *
   * @return the header to use for the Container Name value
   */
  public abstract String getContainerNameHeader();


  /**
   * Get the header name for the Container Name value.
   *
   * @return the header to use for the Container Name value
   */
  public abstract String getTimestampHeader();


  /**
   * Get the header name for the Camel ID value.
   *
   * @return the header to use for the Camel ID value
   */
  public abstract String getCamelIdHeader();


  /**
   * Get the header name for the Route ID value.
   *
   * @return the header to use for the Route ID value
   */
  public abstract String getRouteIdHeader();

  /**
   * Determine if a Container Name value has been configured.
   *
   * @return true if the value has been configured; false otherwise
   */
  public boolean hasContainerName() {
    return containerName != null && !containerName.isEmpty();
  }

  /**
   * Get the value which will be used for the Container Name header.
   *
   * NOTE:  The default value for this attribute is the value of the System "karaf.name" property.
   *
   * @return the value which will be used for the header
   */
  public String getContainerName() {
    return containerName;
  }

  /**
   * Set the value to use for the Container Name header.
   *
   * @param containerName value of the header
   */
  public void setContainerName(String containerName) {
    this.containerName = containerName;
  }

  /**
   * Determine if a Breadcrumb ID value has been configured.
   *
   * @return true if the value has been configured; false otherwise
   */
  public boolean hasBreadcrumbId() {
    return breadcrumbId != null && !breadcrumbId.isEmpty();
  }

  /**
   * Get the value which will be used for the breadcrumb ID header.
   *
   * @return the value which will be used for the header
   */
  public String getBreadcrumbId() {
    return this.breadcrumbId;
  }

  /**
   * Set the value to use for the Breadcrumb ID header.
   *
   * @param breadcrumbId value of the header
   */
  public void setBreadcrumbId(String breadcrumbId) {
    this.breadcrumbId = breadcrumbId;
  }

  /**
   * Add the common headers to the Camel Message from the Exchange.
   *
   * @param exchange the Exchange containing the message.
   *
   * @return the message to which the headers were added
   */
  public Message addCommonHeaders(Exchange exchange) {
    if (exchange == null) {
      throw new NullPointerException("Exchange parameter cannot be null");
    }


    Message message = exchange.getMessage();

    // Check if the breadcrumb ID exists in the message headers. If it does not create one.
    breadcrumbId = message.getHeader(AUDIT_BREADCRUMB_ID_HEADER, String.class);
    if (breadcrumbId == null || breadcrumbId.isEmpty()) {
      breadcrumbId = String.valueOf(java.util.UUID.randomUUID());
      setBreadcrumbId(breadcrumbId);
      // with jms2 we no longer have a jmsId so generate a GUID
      message.getHeaders().put(AUDIT_BREADCRUMB_ID_HEADER, breadcrumbId);
    }
    LOG.debug(AUDIT_BREADCRUMB_ID_HEADER + ":" + breadcrumbId);

    if (hasContainerName()) {
      message.setHeader(getContainerNameHeader(), containerName);
    }
    LOG.debug(getContainerName() + ":" + containerName);
    message.setHeader(getCamelIdHeader(), exchange.getContext().getName());
    LOG.debug(getCamelIdHeader() + ":" + exchange.getContext().getName());
    message.setHeader(getRouteIdHeader(), exchange.getFromRouteId());
    LOG.debug(getRouteIdHeader() + ":" + exchange.getFromRouteId());
    message.setHeader(getTimestampHeader(), System.currentTimeMillis());

    return message;
  }

}
