package org.eclipse.cargotracker.interfaces.handling.file;

import jakarta.enterprise.context.Dependent;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import org.eclipse.cargotracker.application.util.DateConverter;
import org.eclipse.cargotracker.domain.model.cargo.TrackingId;
import org.eclipse.cargotracker.domain.model.handling.HandlingEvent;
import org.eclipse.cargotracker.domain.model.location.UnLocode;
import org.eclipse.cargotracker.domain.model.voyage.VoyageNumber;
import org.eclipse.cargotracker.interfaces.handling.HandlingEventRegistrationAttempt;

@Dependent
public class EventLineParser {

  public HandlingEventRegistrationAttempt parse(String line) throws EventLineParseException {

    String[] result = line.split(",", -1);

    if (result.length != 5) {
      throw new EventLineParseException("Wrong number of data elements", line);
    }

    LocalDateTime completionTime;

    try {
      completionTime = DateConverter.toDateTime(result[0]);
    } catch (DateTimeParseException e) {
      throw new EventLineParseException("Cannot parse completion time", e, line);
    }

    TrackingId trackingId;

    try {
      if (result[1].isBlank()) {
        throw new IllegalArgumentException("Tracking ID is empty");
      }
      trackingId = new TrackingId(result[1]);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new EventLineParseException("Cannot parse tracking ID", e, line);
    }

    VoyageNumber voyageNumber = null;

    try {
      if (!result[2].isBlank()) {
        voyageNumber = new VoyageNumber(result[2]);
      }
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new EventLineParseException("Cannot parse voyage number", e, line);
    }

    UnLocode unLocode;

    try {
      unLocode = new UnLocode(result[3]);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new EventLineParseException("Cannot parse UN location code", e, line);
    }

    HandlingEvent.Type eventType;

    try {
      eventType = HandlingEvent.Type.valueOf(result[4]);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new EventLineParseException("Cannot parse event type", e, line);
    }

    return new HandlingEventRegistrationAttempt(
        LocalDateTime.now(), completionTime, trackingId, voyageNumber, eventType, unLocode);
  }
}
