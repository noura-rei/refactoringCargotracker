package org.eclipse.cargotracker.interfaces.handling.file;

import org.eclipse.cargotracker.interfaces.handling.HandlingEventRegistrationAttempt;

public class EventLineParser {

    // We define the method so the test can find it.
    // Right now, it just throws an exception, so we can see the test fail.
    public HandlingEventRegistrationAttempt parse(String line) throws EventLineParseException {
        throw new UnsupportedOperationException("Not implemented yet");
    }
}