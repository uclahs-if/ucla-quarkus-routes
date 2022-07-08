package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import org.apache.camel.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Processor before auditing messages inside to audit.in or audit.out.
 * This will set up the ability to truncate data or remove the base64 PDF
 */
public class Hl7AuditMessageProcessor {
  Logger log = LoggerFactory.getLogger(this.getClass());

  static final String TRUNCATED = "[[..TRUNCATED]]";
  int maxAuditSize = -1;
  int maxAuditObx5Size = -1;
  boolean removeBase64fromObx5 = false;

  // TODO: Make a generic HL7 message structure to parse every message making the code more consistent with other processing.
  /**
   * Audit HL7 messages.
   * @param message the hl7 message to parse.
   * @return the input body.
   */
  @Handler
  public String process(String message) {
    StringBuffer body = new StringBuffer(message);
    log.debug("body length={}", body.length());
    log.debug("body ={}", body);

    // Remove the base64
    if (removeBase64fromObx5) {
      removeBase64fromObx5(body);
    }

    if (maxAuditObx5Size > 0) {
      removeObxToSize(body);
    }

    if (maxAuditSize > 0) {
      truncateMessageToSize(body);
    }
    return body.toString();
  }

  /**
   * Truncate the message to a specific size.
   * @param body of the message to send.
   */
  void truncateMessageToSize(StringBuffer body) {
    if (body.length() > maxAuditSize) {
      // we need to truncate it
      log.debug("TRUNCATE THE MESSAGE ");
      if (body.length() > maxAuditSize) {
        //still too big
        body.replace(maxAuditSize - TRUNCATED.length(), body.length(), TRUNCATED); // last ditch attempt - we simply truncate it!
      }
    }
  }

  /**
   * Remove OBXs to allow a message to be a specific size.
   * @param body of the input message.
   */
  void removeObxToSize(StringBuffer body) {
    Hl7Util hl7 = new Hl7Util(body.toString());
    log.debug(hl7.encode().replace("\r", "\n"));
    for (int obxCount = 0; obxCount < hl7.getSegmentCount("OBX"); obxCount++) {
      if (hl7.getField("OBX", obxCount, 5).length() > maxAuditObx5Size) { // if greater than our limit
        hl7.setField("OBX", obxCount, 5, "[Removed]");
      }
    }
    log.debug(hl7.encode().replace("\r", "\n"));
    body.replace(0, body.length(), hl7.encode());
  }


  /**
   * Remove the BASE64 pdf inside of obx segments.
   * @param body of the input message.
   */
  void removeBase64fromObx5(StringBuffer body) {
    Hl7Util hl7 = new Hl7Util(body.toString());
    log.debug(hl7.encode().replace("\r", "\n"));
    for (int obxCount = 0; obxCount < hl7.getSegmentCount("OBX"); obxCount++) {
      // Find the embedded dockument by looking for "ED" in OBX-2
      if (hl7.getField("OBX", obxCount, 2).equalsIgnoreCase("ED")) {
        hl7.setField("OBX", obxCount, 5, "[OMIT-BASE64]");
      }
    }
    log.debug(hl7.encode().replace("\r", "\n"));
    body.replace(0, body.length(), hl7.encode());
  }
}