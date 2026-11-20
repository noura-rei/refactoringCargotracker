package org.eclipse.cargotracker.interfaces.handling.file;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

public class EventLineParserTest {

  // We assume we will extract the method into this new class
  private final EventLineParser parser = new EventLineParser();

  @Test
  public void testValidRow() throws EventLineParseException {
    String validLine = "10/1/2023 2:30 PM,ABC123,V001,USNYC,LOAD";

    // We just check that it doesn't throw an exception
    // (and later we will check the actual object it returns)
    parser.parse(validLine);
  }

  @Test
  public void testValidRowWithoutVoyage() throws EventLineParseException {
    // Voyage is optional, so this should pass!
    String validLineNoVoyage = "10/1/2023 2:30 PM,ABC123,,USNYC,LOAD";
    parser.parse(validLineNoVoyage);
  }

  @Test
  public void testWrongNumberOfColumns() {
    String badLine = "10/1/2023 2:30 PM,ABC123,USNYC,LOAD"; // Only 4 columns

    assertThatThrownBy(() -> parser.parse(badLine))
        .isInstanceOf(EventLineParseException.class)
        .hasMessage("Wrong number of data elements");
  }

  @Test
  public void testInvalidDate() {
    String badLine = "NOT_A_DATE,ABC123,V001,USNYC,LOAD";

    assertThatThrownBy(() -> parser.parse(badLine))
        .isInstanceOf(EventLineParseException.class)
        .hasMessage("Cannot parse completion time");
  }

  @Test
  public void testInvalidTrackingId() {
    String badLine = "10/1/2023 2:30 PM,,V001,USNYC,LOAD"; // Empty Tracking ID

    assertThatThrownBy(() -> parser.parse(badLine))
        .isInstanceOf(EventLineParseException.class)
        .hasMessage("Cannot parse tracking ID");
  }

  @Test
  public void testInvalidLocation() {
    String badLine = "10/1/2023 2:30 PM,ABC123,V001,INVALID_LOCATION,LOAD";

    assertThatThrownBy(() -> parser.parse(badLine))
        .isInstanceOf(EventLineParseException.class)
        .hasMessage("Cannot parse UN location code");
  }

  @Test
  public void testInvalidEventType() {
    String badLine = "10/1/2023 2:30 PM,ABC123,V001,USNYC,FLYING";

    assertThatThrownBy(() -> parser.parse(badLine))
        .isInstanceOf(EventLineParseException.class)
        .hasMessage("Cannot parse event type");
  }
}
