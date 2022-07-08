package edu.ucla.mednet.iss.it.quarkus.examples.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Hl7Util {
  List<List<String>> hl7;   // map of segments - if there are more than one, the List contains them all.


  public Hl7Util(String body) {
    hl7 = parseSegments(body);
  }


  /**
   * Set the first segment we find with the field value.
   *
   * @param segmentName the name of hte segment
   * @param field the field
   * @param value value to set
   */
  public void setField(String segmentName, int field, String value) {

    setField(segmentName, 0, field, value);
  }


  /**
   * Set the repeating segment with the field value.
   * @param segmentName the name of hte segment
   * @param segmentNumber the segment number
   * @param field the field
   * @param value value to set
   */
  public void setField(String segmentName, int segmentNumber, int field, String value) {

    int segmentCount = 0;
    for (List<String> segment : hl7) {
      if (segment.get(0).startsWith(segmentName)) {
        if (segmentCount++ == segmentNumber) {
          segment.set(field, value);
        }
      }
    }
  }

  /**
   * Returns the total number of segments, even if they are not contiguous.
   * @param segmentName The segment name to find.
   * @return the number of them.
   */
  public int getSegmentCount(String segmentName) {
    int segmentCount = 0;
    for (List<String> segment : hl7) {
      if (segment.get(0).startsWith(segmentName)) {  // if we have the segment that we are looking for
        segmentCount++ ;
      }
    }
    return segmentCount;
  }

  public String getField(String segmentName, int field) {
    return getField(segmentName, 0, field);
  }

  /**
   * Get the Field inside the hl7 message.
   * @param segmentName segment name.
   * @param segmentNumber the number of segment to get.
   * @param field the field to receive.
   * @return the value of the field or "" if none is found.
   */
  public String getField(String segmentName, int segmentNumber, int field) {
    String fld = "";
    int segmentCount = 0;
    if (segmentName.equalsIgnoreCase("MSH")) {
      field--;  // account for the MSH field numbering due to the encoding characters
    }
    try {
      for (List<String> segment : hl7) {
        if (segment.get(0).startsWith(segmentName)) {  // if we have the segment that we are looking for
          if (segmentCount++ == segmentNumber) {  // and it is the right iteration
            fld = segment.get(field);
          }
        }
      }
    } catch (Exception ex) {
      // do nothing
    }
    return fld;
  }

  public String getSubField(String segment, int field, int subField) {
    return getSubField(segment, 0, field, subField);
  }

  /**
   * Get the subfield from a segment.
   */
  public String getSubField(String segment, int segmentNumber, int field, int subField) {
    try {
      return getField(segment, segmentNumber, field).split("\\^")[--subField];
    } catch (Exception ex) {
      // do nothing
      return "";
    }
  }



  /**
   * Will parse the segments and fields.
   *
   * @param message the message.
   * @return the Segment map.
   */
  private List<List<String>> parseSegments(String message) {

    List<List<String>> segmentList = new ArrayList<>();   // to keep the insertion order, this is the top level list with one entry per segment
    message = message.replace("\n", "\r");
    message = message.replace("\r\r", "\r");

    String[] segments = message.split("\r");
    for (int i = 0; i < segments.length; i++) {
      String segmentType = segments[i].substring(0, 3);
      if (i == 0 && !segmentType.equals("MSH")) {
        // we have a big problem
        return null;
      }

      String[] xx = segments[i].split("\\|");

      System.out.println();
      segmentList.add(Arrays.asList(xx));


    }
    return segmentList;
  }

  /**
   * Encode an hl7 message.
   */
  public String encode() {

    StringBuilder encoded = new StringBuilder();

    for (List<String> segment : hl7) {
      for (String fields : segment) {
        encoded.append(fields).append("|");
      }
      encoded.replace(encoded.length() - 1, encoded.length(), "\r");
    }
    return encoded.toString();
  }


}
