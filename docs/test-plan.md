# Test Plan - Handling Event File Parsing

## Context
We are extracting the parsing logic from `EventItemReader.java` into a new class `EventLineParser.java` to respect the Single Responsibility Principle (SRP).

According to the Batch XML (`EventFilesProcessorJob`), `EventLineParseException` is a "skippable exception". This means if a line is badly formatted, the batch job will log it and skip it rather than crashing the whole file upload. Therefore, these tests are critical to ensure bad data is rejected correctly.

## The Expected Format (Columns)
The parser splits the input string by commas `,` and expects exactly **5 columns**:
1.  **Completion Time** (Date) - e.g., `2023-10-01T14:30`
2.  **Tracking ID** - e.g., `ABC123`
3.  **Voyage Number** (Optional) - e.g., `V001` (can be empty/blank)
4.  **UN Location Code** - e.g., `USNYC`
5.  **Event Type** - e.g., `LOAD`

## Characterization Test Cases

| Test Case | Input Example | Expected Old Behavior |
| :--- | :--- | :--- |
| **Valid Row** | `2023-10-01T14:30,ABC123,V001,USNYC,LOAD` | Parses successfully, returns `HandlingEventRegistrationAttempt` |
| **Valid Row (No Voyage)** | `2023-10-01T14:30,ABC123,,USNYC,LOAD` | Parses successfully (Voyage is optional) |
| **Wrong Number of Columns** | `2023-10-01T14:30,ABC123,USNYC,LOAD` | Throws `EventLineParseException("Wrong number of data elements")` |
| **Invalid Date** | `NOT_A_DATE,ABC123,V001,USNYC,LOAD` | Throws `EventLineParseException("Cannot parse completion time")` |
| **Invalid Tracking ID** | `2023-10-01T14:30,,V001,USNYC,LOAD` | Throws `EventLineParseException("Cannot parse tracking ID")` |
| **Invalid UN/LOCODE** | `2023-10-01T14:30,ABC123,V001,INVALID_LOC,LOAD` | Throws `EventLineParseException("Cannot parse UN location code")` |
| **Invalid Event Type** | `2023-10-01T14:30,ABC123,V001,USNYC,FLYING` | Throws `EventLineParseException("Cannot parse event type")` |
| **Empty Line** | ` ` | Throws `EventLineParseException` (Wrong number of columns) |