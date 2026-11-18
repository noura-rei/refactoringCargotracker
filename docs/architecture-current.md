# CargoTracker — Current Architecture

> All class names, method names and annotations below were read from the source code of the
> project (`src/main/java`, `src/main/resources`, `src/main/webapp`). Dependencies between
> packages were obtained by analysing the `import` statements of every `.java` file.

## 1. Package Architecture Diagram

See [`architecture-current.drawio`](architecture-current.drawio) (open it with diagrams.net).

Dependencies verified from the imports (arrow = "depends on"):

| From | To | Evidence (examples) |
|---|---|---|
| interfaces | application | `Booking` → `DefaultBookingServiceFacade` → `BookingService`; `DateConverter` used by DTOs/web beans |
| interfaces | domain | DTO assemblers (`CargoRouteDtoAssembler`…), facade, `Track`, `CargoTrackingViewAdapter` |
| application | domain | `DefaultBookingService`, `DefaultHandlingEventService`, `DefaultCargoInspectionService` |
| infrastructure | domain | `Jpa*Repository` implement domain repositories; `ExternalRoutingService` implements `RoutingService` |
| infrastructure | application | JMS consumers call `HandlingEventService` / `CargoInspectionService`; `JmsApplicationEvents` implements `ApplicationEvents` |
| infrastructure | pathfinder | `ExternalRoutingService` uses `TransitPath` / `TransitEdge` and calls the pathfinder REST API |
| domain | — | Imports nothing from another layer |
| pathfinder | — | Imports nothing from CargoTracker (independent module) |

Dependencies that go **against** the expected direction (candidates for the refactoring):

| From → To | Where |
|---|---|
| application → interfaces | `ApplicationEvents` imports `interfaces.handling.HandlingEventRegistrationAttempt` |
| infrastructure → interfaces | `JmsApplicationEvents` and `HandlingEventRegistrationAttemptConsumer` use `HandlingEventRegistrationAttempt` |
| interfaces → infrastructure | `RealtimeCargoTrackingService` imports the `@CargoUpdated` qualifier from `infrastructure.events.cdi` |

## 2. Architectural Layers

### 2.1 Domain
The domain layer (`org.eclipse.cargotracker.domain`) contains the core business model of
CargoTracker: the aggregates `Cargo` (with `Itinerary`, `Leg`, `RouteSpecification`, `Delivery`),
`Voyage` (with `CarrierMovement`, `Schedule`), `Location` / `UnLocode` and `HandlingEvent`
(with `HandlingHistory`). It also holds the business rules (e.g. `Cargo.assignToRoute`,
`Cargo.deriveDeliveryProgress`), the `HandlingEventFactory`, the repository interfaces
(`CargoRepository`, `VoyageRepository`, `LocationRepository`, `HandlingEventRepository`),
the `RoutingService` interface and the generic `Specification` classes. It does not import any
other layer, and the JPA entities (`@Entity`) live here.

### 2.2 Application
The application layer (`org.eclipse.cargotracker.application`) coordinates the use cases without
containing business rules itself. `BookingService` / `DefaultBookingService` book a cargo, request
routes, assign a route and change destination or deadline; `HandlingEventService` /
`DefaultHandlingEventService` register handling events; `CargoInspectionService` /
`DefaultCargoInspectionService` re-derive a cargo's delivery progress after it was handled.
`ApplicationEvents` is the interface used to publish application events. The `util` package holds
technical helpers (`ApplicationSettings` entity, `DateConverter`, `RestConfiguration`,
`SampleDataGenerator`).

### 2.3 Infrastructure
The infrastructure layer (`org.eclipse.cargotracker.infrastructure`) provides the technical
implementations of what the domain and application layers declare: JPA repositories
(`persistence.jpa`), JMS messaging (`messaging.jms`: `JmsApplicationEvents` plus the message-driven
consumers), the REST client `ExternalRoutingService` that calls the pathfinder (`routing`), the CDI
event qualifier `CargoUpdated` (`events.cdi`) and the logger producer (`logging`).

### 2.4 Interfaces
The interfaces layer (`org.eclipse.cargotracker.interfaces`) is where users and external systems
enter the application: JSF/PrimeFaces beans in `booking.web` (`Booking`, `CargoDetails`, `ListCargo`,
…) that go through `BookingServiceFacade` and its DTO assemblers, the server-sent-events endpoint
`RealtimeCargoTrackingService` (`booking.sse`), the public tracking page (`tracking.web`), the REST
handling report endpoint `HandlingReportService` (`handling.rest`), the Jakarta Batch components that
import handling event files (`handling.file`) and the `EventLogger` (`handling.mobile`).

### 2.5 Pathfinder
The pathfinder (`org.eclipse.pathfinder`) is a separate, self-contained module that computes
candidate routes. `GraphTraversalService` (`api`) exposes them as a REST resource, `GraphDao`
(`internal`) provides the graph data, and `TransitPath` / `TransitEdge` are the exchanged data
structures. CargoTracker only reaches it through HTTP (`ExternalRoutingService`).

## 3. Booking Flow

Entry point: the page `booking/booking-date.xhtml` has a button with
`action="#{booking.register()}"`. Before that, `Booking.init()` (`@PostConstruct`) called
`bookingServiceFacade.listShippingLocations()` to fill the origin/destination menus.

```
booking-date.xhtml   action="#{booking.register()}"
    |
    v
Booking.register()                         interfaces.booking.web   (@Named, @FlowScoped("booking"))
    |  if origin != destination:
    |  bookingServiceFacade.bookNewCargo(originUnlocode, destinationUnlocode, arrivalDeadline)
    |  else: FacesMessage error, return null
    |  on success returns "/admin/dashboard.xhtml"
    v
BookingServiceFacade.bookNewCargo(String, String, LocalDate)      interfaces.booking.facade (interface)
    |
    v
DefaultBookingServiceFacade.bookNewCargo(...)                     interfaces.booking.facade.internal (@ApplicationScoped)
    |  bookingService.bookNewCargo(new UnLocode(origin), new UnLocode(destination), arrivalDeadline)
    |  returns trackingId.getIdString()
    v
BookingService.bookNewCargo(UnLocode, UnLocode, LocalDate)        application (interface, Bean Validation annotations)
    |
    v
DefaultBookingService.bookNewCargo(...)                           application.internal (@Stateless)
    |  1. cargoRepository.nextTrackingId()
    |  2. locationRepository.find(originUnLocode)      -> Location origin
    |  3. locationRepository.find(destinationUnLocode) -> Location destination
    |  4. new RouteSpecification(origin, destination, arrivalDeadline)
    |  5. new Cargo(trackingId, routeSpecification)
    |  6. cargoRepository.store(cargo)
    |  7. logger.log(... "Booked new cargo with tracking ID {0}")
    v
Domain objects:  Cargo constructor -> Delivery.derivedFrom(routeSpecification, itinerary, HandlingHistory.EMPTY)
Repositories:    JpaCargoRepository.store(cargo)                  infrastructure.persistence.jpa
                   - entityManager.persist(leg) for each itinerary leg, entityManager.persist(cargo)
                   - cargoUpdated.fireAsync(cargo)   (CDI event @CargoUpdated)
                 JpaLocationRepository.find(UnLocode)
    |
    v
RealtimeCargoTrackingService.onCargoUpdated(@ObservesAsync @CargoUpdated Cargo)   interfaces.booking.sse
    (pushes the update to browsers through SSE)
```

Notes:
* `DefaultBookingServiceFacade` also injects `CargoRepository`, `LocationRepository`,
  `VoyageRepository` and `HandlingEventRepository` directly: read operations
  (`listShippingLocations`, `loadCargoForRouting`, `listAllCargos`…) bypass the application layer.
* The same facade serves the other booking use cases: `assignCargoToRoute`, `changeDestination`
  and `changeDeadline` delegate to the same-named `BookingService` methods;
  `requestPossibleRoutesForCargo` leads to `RoutingService.fetchRoutesForSpecification`
  (`ExternalRoutingService`) and then to the pathfinder REST API.

## 4. Handling Upload Flow

There is **no upload page** in the web application (no `upload` in `src/main/webapp`). Files placed
in `/tmp/uploads` are picked up by a timer.

```
UploadDirectoryScanner.processFiles()      interfaces.handling.file  (@Stateless, @Schedule(minute="*/2", hour="*"))
    |  BatchRuntime.getJobOperator().start("EventFilesProcessorJob", null)
    v
META-INF/batch-jobs/EventFilesProcessorJob.xml
    |  job properties: upload_directory=/tmp/uploads, archive_directory=/tmp/archive, failed_directory=/tmp/failed
    |  job listener:  FileProcessorJobListener (logs start / end)
    |  step "ProcessEventFiles": chunk item-count=12, skippable = EventLineParseException
    |  step listener: LineParseExceptionListener
    v
EventItemReader  (@Named("EventItemReader"))
    |  open():     lists files in upload_directory into an EventFilesCheckpoint, opens the first one
    |  readItem(): reads one line (RandomAccessFile.readLine) -> parseLine(line)
    |              line = completionTime,trackingId,voyageNumber,unLocode,eventType (5 fields)
    |              returns a HandlingEventRegistrationAttempt
    |              bad line -> EventLineParseException -> skipped
    |              end of file -> the file is deleted, then the next file is opened
    |
    |--- skipped line --> LineParseExceptionListener.onSkipReadItem()
    |                      appends the line to /tmp/failed/failed_<job>_<instanceId>.csv
    v
EventItemWriter  (@Named("EventItemWriter"))
    |  writeItems(List<Object>) @Transactional, for each attempt:
    |     applicationEvents.receivedHandlingEventRegistrationAttempt(attempt)
    |     and append the attempt to /tmp/archive/archive_<job>_<instanceId>.csv
    v
JmsApplicationEvents.receivedHandlingEventRegistrationAttempt(attempt)     infrastructure.messaging.jms
    |  sends the attempt to the JMS queue  java:app/jms/HandlingEventRegistrationAttemptQueue
    v
HandlingEventRegistrationAttemptConsumer.onMessage(Message)               (@MessageDriven)
    |  handlingEventService.registerHandlingEvent(completionTime, trackingId, voyageNumber, unLocode, type)
    v
DefaultHandlingEventService.registerHandlingEvent(...)                     application.internal (@Stateless)
    |  1. registrationTime = LocalDateTime.now()
    |  2. handlingEventFactory.createHandlingEvent(registrationTime, completionTime, trackingId,
    |                                              voyageNumber, unLocode, type)
    |  3. handlingEventRepository.store(event)
    |  4. applicationEvents.cargoWasHandled(event)
    v
HandlingEventFactory.createHandlingEvent(...)                              domain.model.handling (@ApplicationScoped)
    |  findCargo -> CargoRepository.find        (UnknownCargoException if null)
    |  findVoyage -> VoyageRepository.find      (only if voyageNumber != null; UnknownVoyageException)
    |  findLocation -> LocationRepository.find  (UnknownLocationException)
    |  new HandlingEvent(cargo, completionTime, registrationTime, type, location[, voyage])
    |  invalid data -> CannotCreateHandlingEventException
    v
JpaHandlingEventRepository.store(event)  ->  entityManager.persist(event)  infrastructure.persistence.jpa
```

Follow-up (after the event is stored):

```
DefaultHandlingEventService  -> applicationEvents.cargoWasHandled(event)
    v   JMS queue java:app/jms/CargoHandledQueue (message = tracking id)
CargoHandledConsumer.onMessage -> CargoInspectionService.inspectCargo(TrackingId)
    v
DefaultCargoInspectionService.inspectCargo
    |  cargoRepository.find(trackingId)
    |  handlingEventRepository.lookupHandlingHistoryOfCargo(trackingId)
    |  cargo.deriveDeliveryProgress(handlingHistory)
    |  if misdirected      -> applicationEvents.cargoWasMisdirected(cargo)
    |  if unloaded at dest -> applicationEvents.cargoHasArrived(cargo)
    |  cargoRepository.store(cargo)  -> fires @CargoUpdated -> RealtimeCargoTrackingService (SSE)
```

Notes:
* The batch job is **asynchronous** with respect to the domain: the writer only publishes a JMS
  message; events are created and stored later by the message-driven bean.
* Another entry point feeds the same queue: `HandlingReportService` (`@Path("/handling")`,
  `POST /reports`) calls `applicationEvents.receivedHandlingEventRegistrationAttempt(attempt)`.
* The Javadoc of `UploadDirectoryScanner` says failed files are moved to another directory;
  in the code, only the failing **lines** are written to `failed_directory`, and every processed
  file is deleted by `EventItemReader`.

## 5. Framework Entry Points and Dependencies

| Class | Package | Annotation(s) | Responsibility |
|---|---|---|---|
| Booking | interfaces.booking.web | `@Named`, `@FlowScoped("booking")` | JSF flow bean for booking a cargo |
| CargoDetails | interfaces.booking.web | `@Named` | JSF backing bean displaying cargo details |
| RealtimeCargoTrackingService | interfaces.booking.sse | `@Singleton`, `@Path("/cargo")` | Server-sent events endpoint; observes `@ObservesAsync @CargoUpdated Cargo` |
| HandlingReportService | interfaces.handling.rest | `@Path("/handling")` | REST endpoint receiving handling reports |
| UploadDirectoryScanner | interfaces.handling.file | `@Stateless`, `@Schedule` | Timer that starts the batch job |
| EventItemReader / EventItemWriter | interfaces.handling.file | `@Dependent`, `@Named` | Jakarta Batch reader / writer |
| DefaultBookingServiceFacade | interfaces.booking.facade.internal | `@ApplicationScoped` | Facade between the JSF beans and the application services |
| DefaultBookingService | application.internal | `@Stateless` | Booking use cases |
| DefaultHandlingEventService | application.internal | `@Stateless` | Registers handling events |
| DefaultCargoInspectionService | application.internal | `@Stateless` | Updates delivery progress of a cargo |
| ApplicationSettings | application.util | `@Entity` | Persistent application settings (used by `SampleDataGenerator`) |
| Cargo | domain.model.cargo | `@Entity` | Shipment being transported (aggregate root) |
| Voyage | domain.model.voyage | `@Entity` | Voyage made of carrier movements |
| CarrierMovement | domain.model.voyage | `@Entity` | Movement between two locations |
| Location | domain.model.location | `@Entity` | Place in the transportation network |
| HandlingEvent | domain.model.handling | `@Entity` | Activity in a cargo's journey |
| HandlingEventFactory | domain.model.handling | `@ApplicationScoped` (injects the 3 repositories with `@Inject` fields) | Creates `HandlingEvent` after checking cargo, voyage and location exist |
| Jpa*Repository (4 classes) | infrastructure.persistence.jpa | `@ApplicationScoped`, `@PersistenceContext` | JPA implementations of the repositories |
| JmsApplicationEvents | infrastructure.messaging.jms | `@ApplicationScoped`, `@Resource` queues | Publishes application events on JMS queues |
| *Consumer (5 classes) | infrastructure.messaging.jms | `@MessageDriven` | Receive JMS messages and call application services |
| ExternalRoutingService | infrastructure.routing | `@Stateless` | REST client of the pathfinder |
| GraphTraversalService | org.eclipse.pathfinder.api | `@Stateless`, `@Path("/graph-traversal")` | Graph traversal REST service (shortest paths) |
| GraphDao | org.eclipse.pathfinder.internal | `@ApplicationScoped` | Graph data access for the pathfinder |
