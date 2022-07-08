package edu.ucla.mednet.iss.it.quarkus.examples.utils;

/**
 * Generic exception which can be thrown by components to indicate a configuration error.
 */
public class InvalidConfigurationException extends IllegalStateException {
  public InvalidConfigurationException(String message) {
    super(message);
  }

  public InvalidConfigurationException(String message, Throwable cause) {
    super(message, cause);
  }
}
