package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import java.time.Duration;
import java.time.Instant;
import org.apache.camel.Exchange;
import org.apache.camel.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Camel Predicate that will 'match' when a given number of calls are made to the matches method.
 *
 * By default, the maximum invocation count is zero, which means the predicate will always match.  If the maximum invocation count is set to a value greater than zero,
 * the predicate can be invoked that many times before matching, incrementing an internal counter on each invocation.
 *
 * The invocation count will 'reset' if the matches method is not invoked within the reset interval.
 *
 * The results of the predicate are communicated to the caller with Camel Exchange properties.  However, this can be disabled.
 */
public class InvocationCounterPredicate implements Predicate {
  public static final String DEFAULT_INVOCATION_COUNTER_MATCHED_PROPERTY = "InvocationCounterMatched";
  public static final String DEFAULT_INVOCATION_COUNT_PROPERTY = "InvocationCount";
  public static final String DEFAULT_LAST_INVOCATION_INSTANT_PROPERTY = "LastInvocationInstant";
  public static final String DEFAULT_INVOCATION_DELTA_PROPERTY = "InvocationDelta";

  Logger log = LoggerFactory.getLogger(this.getClass());

  String invocationCounterMatchedProperty = DEFAULT_INVOCATION_COUNTER_MATCHED_PROPERTY;
  String invocationCountProperty = DEFAULT_INVOCATION_COUNT_PROPERTY;
  String lastInvocationInstantProperty = DEFAULT_LAST_INVOCATION_INSTANT_PROPERTY;
  String invocationDeltaProperty = DEFAULT_INVOCATION_DELTA_PROPERTY;

  int maxInvocations = 0;
  Duration invocationCountResetInterval = Duration.ofMillis(60000);

  Instant lastInvocationInstant = Instant.now();
  int invocationCounter = 0;

  @Override
  public boolean matches(Exchange exchange) {
    boolean answer;

    Instant currentInstant = Instant.now();

    Duration delta = Duration.between(lastInvocationInstant, currentInstant);

    switch (maxInvocations) {
      case -1:
        answer = true;
        break;
      case 0:
        answer = false;
        break;
      default:
        if (delta.compareTo(invocationCountResetInterval) > 0) {
          invocationCounter = 1;
        } else {
          invocationCounter += 1;
        }

        answer = invocationCounter <= maxInvocations;
    }

    if (hasInvocationCounterMatchedProperty()) {
      exchange.setProperty(invocationCounterMatchedProperty, answer);
    }

    if (hasInvocationCountProperty()) {
      exchange.setProperty(invocationCountProperty, invocationCounter);
    }

    if (hasLastInvocationInstantProperty()) {
      exchange.setProperty(lastInvocationInstantProperty, lastInvocationInstant);
    }

    if (hasInvocationDeltaProperty()) {
      exchange.setProperty(invocationDeltaProperty, delta.toMillis());
    }

    lastInvocationInstant = currentInstant;

    log.debug("Predicate returning {}; invocationCounter = {}", answer, invocationCounter);

    return answer;
  }

  public boolean hasInvocationCounterMatchedProperty() {
    return invocationCounterMatchedProperty != null && !invocationCounterMatchedProperty.isEmpty();
  }

  public String getInvocationCounterMatchedProperty() {
    return invocationCounterMatchedProperty;
  }

  /**
   * Configures the name of the Camel Exchange property to set with the result of invoking the predicate.
   *
   * If the value is null or empty, the result of the invocation will not be set on the exchange.
   *
   * The default value of this property is "InvocationCounterMatched".
   *
   * @param invocationCounterMatchedProperty the name of the Exhange property to set
   */
  public void setInvocationCounterMatchedProperty(String invocationCounterMatchedProperty) {
    this.invocationCounterMatchedProperty = invocationCounterMatchedProperty;
  }

  public boolean hasInvocationCountProperty() {
    return invocationCountProperty != null && !invocationCountProperty.isEmpty();
  }

  public String getInvocationCountProperty() {
    return invocationCountProperty;
  }

  /**
   * Configures the name of the Camel Exchange property to set with the current number of invocations of the predicate.
   *
   * If the value is null or empty, the result of the current invocation count will not be set on the exchange.
   *
   * The default value of this property is "InvocationCount".
   *
   * @param invocationCountProperty the name of the Exchange property to set
   */
  public void setInvocationCountProperty(String invocationCountProperty) {
    this.invocationCountProperty = invocationCountProperty;
  }

  public boolean hasLastInvocationInstantProperty() {
    return lastInvocationInstantProperty != null && !lastInvocationInstantProperty.isEmpty();
  }

  public String getLastInvocationInstantProperty() {
    return lastInvocationInstantProperty;
  }

  /**
   * Configures the name of the Camel Exchange property to set with the timestamp of the last invocation of the predicate.
   *
   * If the value is null or empty, the invocation timestamp will not be set on the exchange.
   *
   * The default value of this property is "LastInvocationInstant".
   *
   * @param lastInvocationInstantProperty the name of the Exchange property to set
   */
  public void setLastInvocationInstantProperty(String lastInvocationInstantProperty) {
    this.lastInvocationInstantProperty = lastInvocationInstantProperty;
  }

  public boolean hasInvocationDeltaProperty() {
    return invocationDeltaProperty != null && !invocationDeltaProperty.isEmpty();
  }

  public String getInvocationDeltaProperty() {
    return invocationDeltaProperty;
  }

  /**
   * Configures the name of the Camel Exchange property to set with the difference between the invocation timestamp and the previous invocation timestamp.
   *
   * If the value is null or empty, the difference between invocation timestamps will not be set on the exchange.
   *
   * The default value of this property is "InvocationDelta".
   *
   * @param invocationDeltaProperty the name of the Exchange property to set
   */
  public void setInvocationDeltaProperty(String invocationDeltaProperty) {
    this.invocationDeltaProperty = invocationDeltaProperty;
  }

  /**
   * Get the maximum number of errors allowed before shutdown.
   *
   * @return the maximum number of errors allowed before shutdown.
   */
  public int getMaxInvocations() {
    return maxInvocations;
  }

  /**
   * Set the maximum number of errors allowed before the matches method will return 'true'.
   *
   * @param maxInvocations the maximum number of errors allowed
   */
  public void setMaxInvocations(int maxInvocations) {
    this.maxInvocations = (maxInvocations < -1) ? -1 : maxInvocations;
  }

  /**
   * Get the error count reset interval.
   *
   * @return the error count reset interval (in milliseconds).
   */
  public long getInvocationCountResetInterval() {
    return invocationCountResetInterval.toMillis();
  }

  /**
   * Set the error reset count interval.
   *
   * The internal error counter will effectively reset after this amount of time has transpired since the last invocation of the predicate.
   *
   * The default value of this property is 60-sec (60000 milliseconds)
   *
   * @param invocationCountResetInterval the error count reset interval (in milliseconds)
   */
  public void setInvocationCountResetInterval(long invocationCountResetInterval) {
    this.invocationCountResetInterval = Duration.ofMillis(invocationCountResetInterval);
  }
}
