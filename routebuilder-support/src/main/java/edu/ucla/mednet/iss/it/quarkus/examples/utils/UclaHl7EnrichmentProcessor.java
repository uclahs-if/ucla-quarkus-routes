package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.parser.GenericModelClassFactory;
import ca.uhn.hl7v2.parser.ParserConfiguration;
import ca.uhn.hl7v2.util.Terser;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Camel Processor for adding UCLA-specific Camel Headers to Camel Messages by extracting the values from and HL7 Message String.
 */
public class UclaHl7EnrichmentProcessor implements Processor {
  protected boolean cleanPayload = false;
  protected boolean generateHeaders = true;
  protected boolean generateAcknowledgement = true;
  Logger log = LoggerFactory.getLogger(this.getClass());
  String headerPrefix = "ucla_";
  List<String> headerSpecs = Arrays.asList(
      "MSH-3", "MSH-4", "MSH-5", "MSH-6", "MSH-7", "MSH-8", "MSH-9-1", "MSH-9-2", "MSH-10", "MSH-11", "MSH-12", "MSH-18",
      "PID-3-1", "PID-18",
      "PV1-2", "PV1-3", "PV1-19",
      "OBR-2", "OBR-3", "OBR-4-1", "OBR-18", "OBR-25",
      "ORC-1", "ORC-2", "ORC-3", "ORC-5"
  );
  HapiContext hapiContext;
  boolean required = true;
  boolean warnOnParseFailure = true;

  public UclaHl7EnrichmentProcessor() {
    this.hapiContext = new DefaultHapiContext(new ParserConfiguration(),
        ValidationContextFactory.noValidation(), new GenericModelClassFactory());
  }

  public UclaHl7EnrichmentProcessor(HapiContext hapiContext) {
    this.hapiContext = hapiContext;
  }

  @Override
  public void process(Exchange exchange) throws Exception {
    if (exchange == null) {
      throw new IllegalStateException("Exchange cannot be null");
    }

    Message camelMessage = exchange.getMessage();

    String body = camelMessage.getMandatoryBody(String.class);

    if (body == null || body.isEmpty() || !body.startsWith("MSH")) {
      final String logMessageFormat = "Cannot parse body - Headers will not be set because the Camel Message body is not an HL7 message: '{}'";
      if (warnOnParseFailure) {
        log.warn(logMessageFormat, body);
      } else {
        log.debug(logMessageFormat, body);
      }
      if (required) {
        throw new IllegalStateException("Cannot parse body - Headers will not be set because the Camel Message body is not an HL7 message");
      }
    } else {
      try {
        ca.uhn.hl7v2.model.Message hapiMessage = hapiContext.getGenericParser().parse(body);

        if (generateHeaders) {
          populateHl7Headers(hapiMessage, camelMessage.getHeaders());
        }

        if (generateAcknowledgement) {
          populateHl7AcknowledgementProperty(hapiMessage, exchange.getProperties());
        }

        if (cleanPayload) {
          camelMessage.setBody(hapiMessage.encode());
        }
      } catch (HL7Exception hl7ParseEx) {
        if (required) {
          throw hl7ParseEx;
        } else {
          final String logMessageFormat = "Failed to parse body - Headers will not be set due to an exception encountered parsing body '{}'";
          if (warnOnParseFailure) {
            log.warn(logMessageFormat, body, hl7ParseEx);
          } else {
            log.debug(logMessageFormat, body, hl7ParseEx);
          }
        }
      }
    }

  }

  public String getHeaderPrefix() {
    return headerPrefix;
  }

  public void setHeaderPrefix(String headerPrefix) {
    this.headerPrefix = headerPrefix;
  }

  public List<String> getHeaderSpecs() {
    return headerSpecs;
  }

  public void setHeaderSpecs(List<String> headerSpecs) {
    this.headerSpecs = headerSpecs;
  }

  public HapiContext getHapiContext() {
    return hapiContext;
  }

  public void setHapiContext(HapiContext hapiContext) {
    this.hapiContext = hapiContext;
  }

  public boolean isRequired() {
    return required;
  }

  public void setRequired(boolean required) {
    this.required = required;
  }

  public boolean isWarnOnParseFailure() {
    return warnOnParseFailure;
  }

  public void setWarnOnParseFailure(boolean warnOnParseFailure) {
    this.warnOnParseFailure = warnOnParseFailure;
  }

  public boolean isCleanPayload() {
    return cleanPayload;
  }

  public void setCleanPayload(boolean cleanPayload) {
    this.cleanPayload = cleanPayload;
  }

  public boolean isGenerateHeaders() {
    return generateHeaders;
  }

  public void setGenerateHeaders(boolean generateHeaders) {
    this.generateHeaders = generateHeaders;
  }

  public boolean isGenerateAcknowledgement() {
    return generateAcknowledgement;
  }

  public void setGenerateAcknowledgement(boolean generateAcknowledgement) {
    this.generateAcknowledgement = generateAcknowledgement;
  }

  /**
   * Extract the values for the UCLA-specific Camel Messages Headers.
   *
   * @param hapiMessage the source HL7 message
   * @param headers the Camel Message header Map
   */
  protected void populateHl7Headers(ca.uhn.hl7v2.model.Message hapiMessage, Map<String, Object> headers) {
    Terser terser = new Terser(hapiMessage);

    for (String spec : headerSpecs) {
      String headerName = headerPrefix + spec;
      try {
        String value = terser.get(spec);
        if (value != null && !value.isEmpty()) {
          headers.put(headerName, value);
        } else {
          headers.remove(headerName);
        }
      } catch (HL7Exception hl7Ex) {
        headers.remove(headerName);
        log.debug("Value not found for additional header {}{}", headerPrefix, spec);
      }
    }
  }

  /**
   * Generate an HL7 Acknowledgement for the Camel-MLLP component.
   *
   * @param hapiMessage the HL7 Message to acknowledge
   * @param properties the Camel Exchange property Map
   */
  protected void populateHl7AcknowledgementProperty(ca.uhn.hl7v2.model.Message hapiMessage, Map<String, Object> properties) {
    try {
      ca.uhn.hl7v2.model.Message acknowledgement = hapiMessage.generateACK();
      if (acknowledgement != null) {
        properties.put("CamelMllpAcknowledgementString", acknowledgement.encode());
        properties.put("CamelMllpAcknowledgementType", "AA");
      }
    } catch (IOException | HL7Exception generateAckEx) {
      properties.put("CamelMllpAcknowledgementType", "AE");
    }
  }
}
