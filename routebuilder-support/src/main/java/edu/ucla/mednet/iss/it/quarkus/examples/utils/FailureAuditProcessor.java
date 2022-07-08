package edu.ucla.mednet.iss.it.quarkus.examples.utils;

/**
 * Camel processor for generating failure messages.
 *
 * This processor is intended to be used for re-formatting the body of a Camel message to include a
 * reason for failure as well as the stack trace for any exceptions encountered.
 */
public class FailureAuditProcessor extends AuditExceptionProcessorSupport {
  public static final String DEFAULT_FAILURE_MESSAGE = "Route Failed";

  /**
   * Default constructor.
   */
  public FailureAuditProcessor() {
  }

  /**
   * Constructor with a custom failure message.
   *
   * @param failureMessage the customized failure message
   */
  public FailureAuditProcessor(String failureMessage) {
    super(failureMessage);
  }

  @Override
  public String getDefaultAuditMessage() {
    return DEFAULT_FAILURE_MESSAGE;
  }

  public boolean hasFailureMessage() {
    return hasAuditMessage();
  }

  public String getFailureMessage() {
    return getAuditMessage();
  }

  public void setFailureMessage(String failureMessage) {
    setAuditMessage(failureMessage);
  }
}