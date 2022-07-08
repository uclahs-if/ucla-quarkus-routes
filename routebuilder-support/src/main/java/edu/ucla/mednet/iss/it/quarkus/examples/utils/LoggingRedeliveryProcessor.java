package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Simple Camel Processor, intended to be called from a redelivery policy, that logs the number of attempts that have been made to deliver a message.
 */
public class LoggingRedeliveryProcessor implements Processor {
  final Pattern endpointPattern = Pattern.compile("^[^:]+://([^?]+).*");

  Logger log = LoggerFactory.getLogger(this.getClass());

  boolean logFirstRedeliveryAttempt = true;
  int logAfterRedeliveryAttempts = 5;

  /**
   * Logs the attempted redelivery of an exchange.
   *
   * @param exchange the exchange being redelivered
   */
  @Override
  public void process(Exchange exchange) {

    Message message = exchange.getMessage();

    Integer redeliveryCounter = message.getHeader(Exchange.REDELIVERY_COUNTER, Integer.class);

    String camelId = exchange.getContext().getName();
    String routeId = exchange.getFromRouteId();
    String breadcrumbId = message.getHeader(Exchange.BREADCRUMB_ID, String.class);

    String toEndpoint = exchange.getProperty(Exchange.TO_ENDPOINT, String.class);

    if (toEndpoint != null && !toEndpoint.isEmpty()) {
      Matcher matcher = endpointPattern.matcher(toEndpoint);
      if (matcher.matches()) {
        toEndpoint = matcher.group(1);
      }
    }

    if (redeliveryCounter != null) {
      if (logFirstRedeliveryAttempt && redeliveryCounter == 1) {
        final String warningMessageFormat = "Initial redelivery attempt to {} failed (camelId='{}' routeId='{}' breadcrumbId='{}')";
        Exception exception = exchange.getException();
        if (exception == null) {
          exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        }
        if (exception != null) {
          log.warn(warningMessageFormat, toEndpoint, camelId, routeId, breadcrumbId, exception);
        } else {
          log.warn(warningMessageFormat, toEndpoint, camelId, routeId, breadcrumbId);
        }
      } else if ((redeliveryCounter % logAfterRedeliveryAttempts) == 0) {
        final String warningMessageFormat = "Delivery attempt {} to {} failed (camelId='{}' routeId='{}' breadcrumbId='{}')";
        Exception exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        if (exception == null) {
          exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class);
        }
        if (exception != null) {
          log.warn(warningMessageFormat, redeliveryCounter, toEndpoint, camelId, routeId, breadcrumbId, exception);
        } else {
          log.warn(warningMessageFormat, redeliveryCounter, toEndpoint, camelId, routeId, breadcrumbId);
        }
      }
    } else if (logFirstRedeliveryAttempt) {
      final String warningMessageFormat = "Initial redelivery attempt to {} failed (camelId='{}' routeId='{}' breadcrumbId='{}')";
      Exception exception = exchange.getException();
      if (exception != null) {
        log.warn(warningMessageFormat, toEndpoint, camelId, routeId, breadcrumbId, exception);
      } else {
        log.warn(warningMessageFormat, toEndpoint, camelId, routeId, breadcrumbId);
      }
    }
  }

  public boolean isLogFirstRedeliveryAttempt() {
    return logFirstRedeliveryAttempt;
  }

  /**
   * Configures logging of the first redelivery attempt will be logged.
   *
   * The default value of this property is 'true'.
   *
   * @param logFirstRedeliveryAttempt if true, the first redelivery attempt will be logged; otherwise, the first redelivery attempt will not be logged.
   */
  public void setLogFirstRedeliveryAttempt(boolean logFirstRedeliveryAttempt) {
    this.logFirstRedeliveryAttempt = logFirstRedeliveryAttempt;
  }

  /**
   * Get the value of the redelivery counter modulus used to reduce logging noise.
   *
   * @return the value of the log redelivery counter modulus
   */
  public int getLogAfterRedeliveryAttempts() {
    return logAfterRedeliveryAttempts;
  }

  /**
   * Set the value of the log redelivery counter modulus, which can be used to reduce logging volume/noise.
   *
   * This value effectively sets 'log every nth attempt'
   *
   * The default value of this property is '5'.

   * @param logAfterRedeliveryAttempts the value of the log redelivery counter modulus
   */
  public void setLogAfterRedeliveryAttempts(int logAfterRedeliveryAttempts) {
    this.logAfterRedeliveryAttempts = logAfterRedeliveryAttempts;
  }
}
