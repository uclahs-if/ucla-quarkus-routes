package edu.ucla.mednet.iss.it.quarkus.examples.route.config;

import java.util.Optional;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

@ConfigMapping(prefix = "mllp.inbound")
public interface MllpInConfig {
  int port();

  Optional<Integer> receiveBufferSize();


  /**
   * We are setting this to true as we are getting maximum connection issues on the inbound side.
   * 
   * This setting sets the SO_REUSEADDR on the tcp connection allowing us to bind to the listening connection.
   * 
   * https://stackoverflow.com/questions/23123395/what-is-the-purpose-of-setreuseaddress-in-serversocket
   * https://stackoverflow.com/questions/3229860/what-is-the-meaning-of-so-reuseaddr-setsockopt-option-linux
   *
   */
  @WithDefault("true")
  Boolean reuseAddress();


  @WithDefault("1")
  Integer maxConcurrentConsumers();


  Optional<Boolean> jmsExceptionHandled();

  Optional<String> mllpErrorAckType();

  @WithDefault("true")
  boolean jmsExceptionShutdown();

  @WithDefault("0")
  int jmsExceptionMximumRedeliveries();

  @WithDefault("true")
  String targetDestinationTransacted();
}