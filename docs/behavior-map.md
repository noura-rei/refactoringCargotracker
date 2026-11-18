# Cargo Tracker - Behavior Map & Domain Analysis

This document records the current behavior of the Cargo Tracker domain before refactoring. It acts as our shared mental model and safety net.

## 1. Key Terms (Glossary)
*   **Cargo:** The main item being shipped.
*   **TrackingId:** The unique identifier used to track the Cargo.
*   **RouteSpecification:** The customer's requirements (origin, destination, and deadline).
*   **Itinerary:** The actual plan of voyage legs created by the routing system to get the cargo to its destination.
*   **Leg:** A single part of the journey on a specific voyage.
*   **HandlingEvent:** A physical event that happens to the cargo (e.g., LOAD, UNLOAD, RECEIVE, CLAIM, CUSTOMS).
*   **HandlingHistory:** A chronological list of all handling events for a specific cargo.
*   **TransportStatus:** The current physical state of the cargo (NOT_RECEIVED, IN_PORT, ONBOARD_CARRIER, CLAIMED, UNKNOWN).
*   **RoutingStatus:** The state of the cargo's itinerary (ROUTED, NOT_ROUTED, MISROUTED).

## 2. Cargo Lifecycle Flow (In Our Own Words)
1.  **Booking:** A Booking creates a `Cargo` with a unique `TrackingId` and a `RouteSpecification` (origin, destination, deadline).
2.  **Routing:** The system assigns an `Itinerary` (a list of `Leg`s) to the Cargo.
3.  **Handling:** Clerks record `HandlingEvent`s as the cargo moves. These are stored in a `HandlingHistory`.
4.  **Delivery Calculation:** The `Delivery` object looks at the `Itinerary` and the most recent `HandlingEvent` to calculate the cargo's current status.
5.  **Presentation:** The UI/Interfaces read the `Delivery` data (status, ETA, next expected activity) and present it to the user.

## 3. Deep Dive: Delivery.java (The "God Class")
`Delivery.java` is a key refactoring target. It is 378 lines long and currently violates **SRP** and **OCP**.

### A. SRP Violation (Too Many Responsibilities)
The constructor of `Delivery` calculates **8 different things**:
1.  Misdirection Status
2.  Routing Status
3.  Transport Status
4.  Last Known Location
5.  Current Voyage
6.  ETA (Estimated Time of Arrival)
7.  Next Expected Activity
8.  Unloaded at Destination Status

### B. OCP Violation (Switch Statements)
We found two `switch` statements on `lastEvent.getType()` inside `Delivery.java`:

**Switch 1: `calculateTransportStatus()`**
*   **LOAD** -> `ONBOARD_CARRIER`
*   **UNLOAD, RECEIVE, CUSTOMS** -> `IN_PORT`
*   **CLAIM** -> `CLAIMED`
*   **Anything else / null** -> `UNKNOWN` (or `NOT_RECEIVED` if lastEvent is null)

**Switch 2: `calculateNextExpectedActivity()`**
*   **LOAD:** Finds the specific Leg based on the load location and returns the expected UNLOAD activity. If not found -> `NO_ACTIVITY`.
*   **UNLOAD:** Finds the Leg based on unload location. If there is a next leg -> expected LOAD. If it's the final leg -> expected CLAIM.
*   **RECEIVE:** Returns expected LOAD for the first leg of the itinerary.
*   **CLAIM / Default:** Returns `NO_ACTIVITY`.

## 4. Current Edge Cases & Business Rules (Found in Code)
*   **No Handling Events:** If `lastEvent` is null, status is `NOT_RECEIVED`. Misdirection is `false`.
*   **No Itinerary:** If `itinerary` is null or empty, RoutingStatus is `NOT_ROUTED`.
*   **Misdirection:** A cargo is misdirected if `lastEvent` is NOT in the itinerary. (A cargo with no itinerary or no events cannot be misdirected).
*   **Unloaded at Destination:** True only if the last event is UNLOAD *and* the location matches the RouteSpecification destination.
*   **On Track:** A boolean helper that is `true` only if RoutingStatus is `ROUTED` AND the cargo is NOT misdirected. (ETA and NextActivity are only calculated if `onTrack()` is true).

## 5. Testing Status
*   **Current Baseline:** 24 tests pass successfully (`CargoTest`, `ItineraryTest`, `RouteSpecificationTest`, `HandlingEventTest`, `HandlingHistoryTest`).
*   **Missing Tests:** There is NO `DeliveryTest.java` and NO `TransportStatusTest.java`. `Delivery` is only tested indirectly through `CargoTest`.

## 6. Questions & Action Items (For Amina & Noura to Discuss)
1.  **Pattern Choice:** For replacing the `calculateTransportStatus()` switch, should we put the logic inside the `TransportStatus` enum, or create a separate `Strategy` class?
2.  **Test Creation:** Since `DeliveryTest` is missing, who will write the behavior test for `Delivery` before we start breaking it apart? (Amina or Noura?)
3.  **UI Dependency:** Noura needs to verify how the UI currently reads `Delivery`. Does it use `getTransportStatus()`, or does it recalculate anything?