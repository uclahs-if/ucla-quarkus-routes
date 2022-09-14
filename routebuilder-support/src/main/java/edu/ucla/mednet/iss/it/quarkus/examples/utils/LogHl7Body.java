package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import org.apache.camel.Exchange;
import org.apache.camel.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogHl7Body {

  static final Logger LOG = LoggerFactory.getLogger(LogHl7Body.class);
  int maxLength = 100;

  public LogHl7Body() {
  }


  public LogHl7Body(int maxLength) {
    this.maxLength = maxLength;
  }

  /**
   * Log the message replacing \r and \n.
   */
  @Handler
  public void process(Exchange exchange) throws Exception {
    String body = exchange.getMessage().getBody(String.class);
    LOG.info(
        body
            .substring(0, Math.min(maxLength, body.length()))
            .replaceAll("\r","<CR>")
            .replaceAll("\n","<LF>")
    );
  }
}

