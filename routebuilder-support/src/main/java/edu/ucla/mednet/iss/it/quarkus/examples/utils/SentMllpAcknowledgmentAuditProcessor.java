package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;

/**
 * Processor for setting the Camel Message body to the MLLP Acknowledgement.
 */
public class SentMllpAcknowledgmentAuditProcessor implements Processor {
  public static final String MLLP_ACKNOWLEDGEMENT_STRING = "CamelMllpAcknowledgementString";
  public static final String MLLP_ACKNOWLEDGEMENT = "CamelMllpAcknowledgement";
  public static final String MLLP_CHARSET = "CamelMllpCharset";
  public static final String MLLP_AUTO_ACKNOWLEDGE = "CamelMllpAutoAcknowledge";


  /**
   * Modifies the provided exchange, replacing the Camel Message body with the value contained in the 'CamelMllpAcknowledgementString' or the 'CamelMllpAcknowledgement' header.
   *
   * Both of the headers are removed.
   *
   * @param exchange the exchange to modify
   */
  @Override
  public void process(Exchange exchange) throws Exception {
    Message message = exchange.getMessage();

    if (message.hasHeaders()) {
      Object acknowledgementStringObject = message.getHeader(MLLP_ACKNOWLEDGEMENT_STRING);
      if (acknowledgementStringObject != null && !acknowledgementStringObject.toString().isEmpty()) {
        message.setBody(acknowledgementStringObject);
      } else {
        String acknowledgementString = "";

        Object acknowledgementBytesObject = message.getHeader(MLLP_ACKNOWLEDGEMENT);
        if (acknowledgementBytesObject != null) {
          byte[] acknowledgementBytes = (byte[]) acknowledgementBytesObject;
          if (acknowledgementBytes.length > 0) {
            Charset charset = StandardCharsets.ISO_8859_1;
            String mllpCharsetString = exchange.getProperty(MLLP_CHARSET, String.class);
            if (mllpCharsetString != null && !mllpCharsetString.isEmpty()) {
              if (Charset.isSupported(mllpCharsetString)) {
                charset = Charset.forName(mllpCharsetString);
              }
            }
            acknowledgementString = new String(acknowledgementBytes, charset);
          }
        }

        message.setBody(acknowledgementString);
      }

      message.removeHeaders(MLLP_ACKNOWLEDGEMENT + "*");
      message.removeHeader(MLLP_AUTO_ACKNOWLEDGE);
    }
  }

}
