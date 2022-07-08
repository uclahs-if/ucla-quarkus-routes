package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

/**
 * Base Camel processor to support the generation of audit message bodies when exceptions are encountered.
 *
 * This processor is intended to be used for re-formatting the body of a Camel message to include a
 * message to audit as well as the stack trace for any exceptions encountered.
 */
public abstract class AuditExceptionProcessorSupport implements Processor {
  public static final String AUDIT_MESSAGE_FORMAT = "%s: contextId = '%s' routeId='%s'";
  String auditMessage;

  /**
   * Default constructor.
   */
  public AuditExceptionProcessorSupport() {
  }

  /**
   * Constructor with a custom failure message.
   *
   * @param auditMessage the customized failure message
   */
  public AuditExceptionProcessorSupport(String auditMessage) {
    this.auditMessage = auditMessage;
  }

  /**
   * Modifies the provided exchange, replacing the Camel Message body with information about the failure, as well as the current payload state/value.
   *
   * @param exchange the exchange to modify
   */
  @Override
  public void process(Exchange exchange) {
    if (exchange == null) {
      throw new IllegalStateException("Exchange cannot be null");
    }

    StringBuilder auditPayloadBuilder = new StringBuilder();

    appendAuditMessage(auditPayloadBuilder, exchange);
    appendExceptionSummary(auditPayloadBuilder, exchange);
    appendCurrentBody(auditPayloadBuilder, exchange);
    appendStackTrace(auditPayloadBuilder, exchange);

    auditPayloadBuilder.append('\n');

    String auditPayload = auditPayloadBuilder.toString();

    Message message = exchange.getMessage();
    message.setBody(auditPayload);
  }

  public boolean hasAuditMessage() {
    return auditMessage != null && !auditMessage.isEmpty();
  }

  public String getAuditMessage() {
    return auditMessage;
  }

  public void setAuditMessage(String auditMessage) {
    this.auditMessage = auditMessage;
  }

  /**
   * Append the formatted audit message to the supplied StringBuilder.
   *
   * @param auditPayloadBuilder the StringBuilder to append the audit message
   * @param exchange the source of the audit information
   */
  protected void appendAuditMessage(StringBuilder auditPayloadBuilder, Exchange exchange) {
    String auditFailureBody = String.format(AUDIT_MESSAGE_FORMAT,
        hasAuditMessage() ? getAuditMessage() : getDefaultAuditMessage(),
        exchange.getContext().getName(), exchange.getFromRouteId());

    auditPayloadBuilder.append(auditFailureBody);
  }

  /**
   * Append the current Message body to the supplied StringBuilder, transforming \r characters to the {@literal <CR>} String.
   *
   * @param auditPayloadBuilder the StringBuilder to append the Message body
   * @param exchange the source of the audit information
   */
  protected void appendCurrentBody(StringBuilder auditPayloadBuilder, Exchange exchange) {
    Message message = exchange.getMessage();

    String stringBody = message.getBody(String.class);
    if (stringBody != null && !stringBody.isEmpty()) {
      auditPayloadBuilder
          .append('\n').append('\n')
          .append(stringBody.replaceAll("\r", "<CR>\n"));
    }
  }

  /**
   * Append the summary information about any exception in the exchange to the supplied StringBuilder.
   *
   * @param auditPayloadBuilder the StringBuilder to append the exception summary
   * @param exchange the source of the audit information
   */
  protected void appendExceptionSummary(StringBuilder auditPayloadBuilder, Exchange exchange) {
    Throwable exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
    if (exception != null) {
      auditPayloadBuilder.append("\n\nException Class: ")
          .append(exception.getClass().getName())
          .append("\nException Message: ")
          .append(exception.getMessage());

      while ((exception = exception.getCause()) != null) {
        auditPayloadBuilder
            .append("\n\tCaused by Exception Class: ")
            .append(exception.getClass().getName())
            .append("\n\tCaused by Exception Message: ")
            .append(exception.getMessage());
      }
    }
  }

  /**
   * Append the full stack trace for any exception in the exchange to the supplied StringBuilder.
   *
   * @param auditPayloadBuilder the StringBuilder to append the stack trace
   * @param exchange the source of the audit information
   */
  protected void appendStackTrace(StringBuilder auditPayloadBuilder, Exchange exchange) {
    Throwable exception = exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Throwable.class);
    if (exception != null) {
      auditPayloadBuilder
          .append("\n\n")
          .append(exception.getClass().getName())
          .append(": ")
          .append(exception.getMessage());

      StackTraceElement[] stackTraceElements = exception.getStackTrace();
      if (stackTraceElements != null && stackTraceElements.length > 0) {
        for (StackTraceElement stackTraceElement : stackTraceElements) {
          auditPayloadBuilder
              .append("\n\t at ")
              .append(stackTraceElement.toString());
        }
      }

      while ((exception = exception.getCause()) != null) {
        auditPayloadBuilder
            .append("\nCaused by: ")
            .append(exception.getClass().getName())
            .append(": ")
            .append(exception.getMessage());

        stackTraceElements = exception.getStackTrace();
        if (stackTraceElements != null && stackTraceElements.length > 0) {
          for (StackTraceElement stackTraceElement : stackTraceElements) {
            auditPayloadBuilder
                .append("\n\t at ")
                .append(stackTraceElement.toString());
          }
        }
      }
    }
  }

  /**
   * Provide a default audit message in the event the auditMessage property is null or empty.
   *
   * @return a default audit message.
   */
  public abstract String getDefaultAuditMessage();
}