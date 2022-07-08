package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

/**
 * Processor for setting the Camel Message body to the MLLP Acknowledgement.
 */
public class ReceivedMllpAcknowledgementProcessor implements Processor {
  public static final String MLLP_ACKNOWLEDGEMENT = "CamelMllpAcknowledgement";
  public static final String MLLP_ACKNOWLEDGEMENT_STRING = "CamelMllpAcknowledgementString";
  public static final String MLLP_AUTO_ACKNOWLEDGE = "CamelMllpAutoAcknowledge";

  /**
   * Modifies the provided exchange, replacing the Camel Message body with the value contained
   * in the 'CamelMllpAcknowledgementString' or the 'CamelMllpAcknowledgement' header.
   *
   * Both of the headers are removed.
   *
   * @param exchange the exchange to modify
   */
  @Override
  public void process(Exchange exchange) {
    Message message = exchange.getMessage();

    if (message.hasHeaders()) {
      Object acknowledgementStringObject = message.getHeader(MLLP_ACKNOWLEDGEMENT_STRING);
      if (acknowledgementStringObject != null && !acknowledgementStringObject.toString().isEmpty()) {
        message.setBody(acknowledgementStringObject);
      } else {
        Object acknowledgementBytesObject = message.getHeader(MLLP_ACKNOWLEDGEMENT);
        if (acknowledgementBytesObject != null) {
          byte[] acknowledgementBytes = (byte[]) acknowledgementBytesObject;
          if (acknowledgementBytes.length > 0) {
            //TODO: check if in new version IOHelper.newStringFromBytes is back.
            //message.setBody(IOHelper.newStringFromBytes(acknowledgementBytes));
            message.setBody(acknowledgementBytes);
          } else {
            message.setBody("");
          }
        }
      }

      message.removeHeaders(MLLP_ACKNOWLEDGEMENT + "*");
      message.removeHeader(MLLP_AUTO_ACKNOWLEDGE);
    }
  }

}
