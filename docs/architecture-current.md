CargoTracker --- Current Architecture
Class names, method names, annotations, and flows below are based on
the project source and the architecture details supplied for this
review. Package dependencies were identified from Java imports. This
document describes the current implementation, not a proposed target
architecture.

1. Package Architecture Diagram
   See architecture-current.drawio and
   open it with diagrams.net.
   1.1 Dependencies verified from imports
   An arrow means depends on.
   From                    To                      Evidence
   interfaces            application           Booking →
   DefaultBookingServiceFacade →
   BookingService; DateConverter
   used by DTOs/web beans

interfaces            domain                DTO assemblers such as
CargoRouteDtoAssembler, facade,
Track,
CargoTrackingViewAdapter

application           domain                DefaultBookingService,
DefaultHandlingEventService,
DefaultCargoInspectionService

infrastructure        domain                Jpa*Repository implement domain
repository interfaces;
ExternalRoutingService
implements RoutingService

infrastructure        application           JMS consumers call
HandlingEventService /
CargoInspectionService;
JmsApplicationEvents implements
ApplicationEvents

infrastructure        pathfinder            ExternalRoutingService uses
TransitPath / TransitEdge and
calls the pathfinder REST API

domain                ---                     Imports nothing from another
CargoTracker layer
pathfinder            ---                     Imports nothing from
CargoTracker; independent module
1.2 Dependencies against the expected direction
These are candidates for refactoring.
From → To                           Evidence
application → interfaces        ApplicationEvents imports
interfaces.handling.HandlingEventRegistrationAttempt

infrastructure → interfaces     JmsApplicationEvents and
HandlingEventRegistrationAttemptConsumer use
HandlingEventRegistrationAttempt
interfaces → infrastructure     RealtimeCargoTrackingService imports @CargoUpdated
from infrastructure.events.cdi
2. Architectural Layers
   2.1 Domain --- org.eclipse.cargotracker.domain
   The domain layer contains the core business model and rules. It imports
   no other CargoTracker layer.
- Aggregates and objects: Cargo (with Itinerary, Leg,
  RouteSpecification, Delivery), Voyage (with CarrierMovement,
  Schedule), Location / UnLocode, and HandlingEvent (with
  HandlingHistory).
- Business rules: Cargo.assignToRoute,
  Cargo.deriveDeliveryProgress.
- Factory and services: HandlingEventFactory, RoutingService.
- Repository interfaces: CargoRepository, VoyageRepository,
  LocationRepository, HandlingEventRepository.
- Specifications: generic Specification classes and combinators.
- Persistence mapping: JPA @Entity classes, including Cargo,
  Voyage, Location, and HandlingEvent, live in the domain model.
  2.2 Application --- org.eclipse.cargotracker.application
  Coordinates use cases and delegates business decisions to the domain.
- BookingService / DefaultBookingService: book cargo, request
  routes, assign routes, change destination and deadline.
- HandlingEventService / DefaultHandlingEventService: register
  handling events.
- CargoInspectionService / DefaultCargoInspectionService:
  re-derive delivery progress after handling.
- ApplicationEvents: interface used to publish application events.
- util: ApplicationSettings entity, DateConverter,
  RestConfiguration, and SampleDataGenerator.
  2.3 Infrastructure --- org.eclipse.cargotracker.infrastructure
  Provides technical implementations.
- Persistence: JPA repositories in persistence.jpa.
- Messaging: messaging.jms, including JmsApplicationEvents and
  message-driven consumers.
- Routing: ExternalRoutingService, the REST client for the
  pathfinder.
- CDI events: CargoUpdated qualifier in events.cdi.
- Logging: logger producer in logging.
  2.4 Interfaces --- org.eclipse.cargotracker.interfaces
  Entry points for users and external systems.
- JSF / PrimeFaces: booking.web beans such as Booking,
  CargoDetails, and ListCargo, using BookingServiceFacade and
  DTO assemblers.
- SSE: RealtimeCargoTrackingService in booking.sse.
- Tracking: public tracking page in tracking.web.
- REST: HandlingReportService in handling.rest.
- Batch: Jakarta Batch components in handling.file.
- Mobile: EventLogger in handling.mobile.
  2.5 Pathfinder --- org.eclipse.pathfinder
  A separate module computing candidate routes.
- GraphTraversalService (api) exposes the REST resource.
- GraphDao (internal) provides graph data.
- TransitPath / TransitEdge are exchanged data structures.
- CargoTracker reaches it through HTTP using ExternalRoutingService.
3. Booking Flow
   3.1 Entry point
   booking/booking-date.xhtml has a button with
   action="#{booking.register()}". Before registration, Booking.init()
   (@PostConstruct) calls bookingServiceFacade.listShippingLocations()
   to populate origin and destination menus.
   3.2 Sequence
   booking-date.xhtml  action="#{booking.register()}"
   |
   v
   Booking.register()  interfaces.booking.web
   (@Named, @FlowScoped("booking"))
   |
   | If origin != destination:
   |   bookingServiceFacade.bookNewCargo(
   |     originUnlocode, destinationUnlocode, arrivalDeadline)
   | Else: FacesMessage error; return null
   | On success: return "/admin/dashboard.xhtml"
   v
   BookingServiceFacade.bookNewCargo(String, String, LocalDate)
   interfaces.booking.facade (interface)
   |
   v
   DefaultBookingServiceFacade.bookNewCargo(...)
   interfaces.booking.facade.internal (@ApplicationScoped)
   |
   | bookingService.bookNewCargo(
   |   new UnLocode(origin), new UnLocode(destination), arrivalDeadline)
   | returns trackingId.getIdString()
   v
   BookingService.bookNewCargo(UnLocode, UnLocode, LocalDate)
   application (interface; Bean Validation annotations)
   |
   v
   DefaultBookingService.bookNewCargo(...)
   application.internal (@Stateless)
   |
   | 1. cargoRepository.nextTrackingId()
   | 2. locationRepository.find(originUnlocode) -> origin
   | 3. locationRepository.find(destinationUnlocode) -> destination
   | 4. new RouteSpecification(origin, destination, arrivalDeadline)
   | 5. new Cargo(trackingId, routeSpecification)
   | 6. cargoRepository.store(cargo)
   | 7. log booking and tracking ID
   v
   Domain and repositories
   Cargo constructor -> Delivery.derivedFrom(
   routeSpecification, itinerary, HandlingHistory.EMPTY)
   |
   v
   JpaCargoRepository.store(cargo)  infrastructure.persistence.jpa
- persists each itinerary leg (when present)
- persists cargo
- cargoUpdated.fireAsync(cargo), with @CargoUpdated
  |
  v
  RealtimeCargoTrackingService.onCargoUpdated(...)
  interfaces.booking.sse
  (@ObservesAsync @CargoUpdated Cargo)
  |
  v
  Pushes update to browsers through SSE
  3.3 Additional facade responsibilities
  DefaultBookingServiceFacade also injects CargoRepository,
  LocationRepository, VoyageRepository, and HandlingEventRepository
  directly. Read operations such as listShippingLocations,
  loadCargoForRouting, and listAllCargos bypass the application layer.

The facade also exposes assignCargoToRoute, changeDestination, and
changeDeadline, delegating to the corresponding BookingService
methods.

requestPossibleRoutesForCargo leads to
RoutingService.fetchRoutesForSpecification, implemented by
ExternalRoutingService, and then to the pathfinder REST API.

Review point: determine whether facade-level repository reads should
be moved behind application query/use-case methods. Preserve the facade
contract and UI behavior during refactoring.
4. Handling Upload Flow
   4.1 Entry point
   There is no upload page in src/main/webapp. Files placed in
   /tmp/uploads are picked up by a timer.
   4.2 Batch-to-JMS-to-domain sequence
   UploadDirectoryScanner.processFiles()
   interfaces.handling.file (@Stateless, @Schedule(minute="*/2", hour="*"))
   |
   | BatchRuntime.getJobOperator().start("EventFilesProcessorJob", null)
   v
   META-INF/batch-jobs/EventFilesProcessorJob.xml
   properties:
   upload_directory=/tmp/uploads
   archive_directory=/tmp/archive
   failed_directory=/tmp/failed
   job listener: FileProcessorJobListener
   step: ProcessEventFiles
   chunk item-count: 12
   skippable: EventLineParseException
   step listener: LineParseExceptionListener
   |
   v
   EventItemReader (@Named("EventItemReader"))
   open():
   lists files in upload_directory into EventFilesCheckpoint
   opens first file
   readItem():
   RandomAccessFile.readLine() -> parseLine(line)
   expected fields:
   completionTime,trackingId,voyageNumber,unLocode,eventType
   valid line -> HandlingEventRegistrationAttempt
   invalid line -> EventLineParseException -> skipped
   end of file -> file deleted, then next file opened
   |
   +-- skipped line --> LineParseExceptionListener.onSkipReadItem()
   |                    appends line to
   |                    /tmp/failed/failed_<job>_<instanceId>.csv
   v
   EventItemWriter (@Named("EventItemWriter"))
   writeItems(List<Object>) [@Transactional]
   for each attempt:
   applicationEvents.receivedHandlingEventRegistrationAttempt(attempt)
   append attempt to /tmp/archive/archive_<job>_<instanceId>.csv
   |
   v
   JmsApplicationEvents.receivedHandlingEventRegistrationAttempt(attempt)
   infrastructure.messaging.jms
   sends to java:app/jms/HandlingEventRegistrationAttemptQueue
   |
   v
   HandlingEventRegistrationAttemptConsumer.onMessage(Message)
   infrastructure.messaging.jms (@MessageDriven)
   calls handlingEventService.registerHandlingEvent(
   completionTime, trackingId, voyageNumber, unLocode, type)
   |
   v
   DefaultHandlingEventService.registerHandlingEvent(...)
   application.internal (@Stateless)
1. registrationTime = LocalDateTime.now()
2. handlingEventFactory.createHandlingEvent(
   registrationTime, completionTime, trackingId, voyageNumber, unLocode, type)
3. handlingEventRepository.store(event)
4. applicationEvents.cargoWasHandled(event)
   |
   v
   HandlingEventFactory.createHandlingEvent(...)
   domain.model.handling (@ApplicationScoped)
- findCargo -> CargoRepository.find
  UnknownCargoException if null
- findVoyage -> VoyageRepository.find only if voyageNumber != null
  UnknownVoyageException if missing
- findLocation -> LocationRepository.find
  UnknownLocationException if missing
- creates HandlingEvent(cargo, completionTime, registrationTime,
  type, location[, voyage])
- invalid data -> CannotCreateHandlingEventException
  |
  v
  JpaHandlingEventRepository.store(event)
  -> entityManager.persist(event)
  4.3 Follow-up after event storage
  DefaultHandlingEventService
  -> applicationEvents.cargoWasHandled(event)
  |
  v
  JMS queue: java:app/jms/CargoHandledQueue
  message = tracking ID
  |
  v
  CargoHandledConsumer.onMessage(...)
  -> CargoInspectionService.inspectCargo(TrackingId)
  |
  v
  DefaultCargoInspectionService.inspectCargo(...)
- cargoRepository.find(trackingId)
- handlingEventRepository.lookupHandlingHistoryOfCargo(trackingId)
- cargo.deriveDeliveryProgress(handlingHistory)
- if misdirected: applicationEvents.cargoWasMisdirected(cargo)
- if unloaded at destination: applicationEvents.cargoHasArrived(cargo)
- cargoRepository.store(cargo)
  |
  v
  JpaCargoRepository fires @CargoUpdated
  |
  v
  RealtimeCargoTrackingService pushes update through SSE
  4.4 Important observations
1. Asynchronous processing: the batch writer publishes a JMS
   message. The event is created and stored later by the message-driven
   consumer.
2. Second entry point: HandlingReportService
   (@Path("/handling"), POST /reports) also calls
   applicationEvents.receivedHandlingEventRegistrationAttempt(attempt)
   and feeds the same queue.
3. Javadoc/code discrepancy: UploadDirectoryScanner Javadoc says
   failed files are moved. The described code writes failing lines
   to failed_directory, while EventItemReader deletes each
   processed file. Verify the operational contract before changing this
   behavior.
4. Failure semantics: skipped-line handling, checkpointing,
   archiving, deletion, and JMS publication are distinct
   responsibilities. Add characterization tests before changing them.
5. Framework Entry Points and Dependencies
   Class                             Package                                Annotation(s)                 Responsibility
   Booking                         interfaces.booking.web               @Named,                     JSF flow bean for booking
   @FlowScoped("booking")

CargoDetails                    interfaces.booking.web               @Named                      JSF backing bean displaying cargo
details

RealtimeCargoTrackingService    interfaces.booking.sse               @Singleton,                 SSE endpoint; observes
@Path("/cargo")             @ObservesAsync @CargoUpdated Cargo

HandlingReportService           interfaces.handling.rest             @Path("/handling")          REST endpoint receiving handling
reports

UploadDirectoryScanner          interfaces.handling.file             @Stateless, @Schedule     Timer starting the batch job

EventItemReader /               interfaces.handling.file             @Dependent, @Named        Jakarta Batch reader / writer
EventItemWriter

DefaultBookingServiceFacade     interfaces.booking.facade.internal   @ApplicationScoped          Facade between JSF beans and
application services; also performs
direct repository reads

DefaultBookingService           application.internal                 @Stateless                  Booking use cases

DefaultHandlingEventService     application.internal                 @Stateless                  Registers handling events

DefaultCargoInspectionService   application.internal                 @Stateless                  Updates cargo delivery progress

ApplicationSettings             application.util                     @Entity                     Persistent application settings

Cargo                           domain.model.cargo                   @Entity                     Shipment; aggregate root

Voyage                          domain.model.voyage                  @Entity                     Voyage made of carrier movements

CarrierMovement                 domain.model.voyage                  @Entity                     Movement between two locations

Location                        domain.model.location                @Entity                     Place in the transportation network

HandlingEvent                   domain.model.handling                @Entity                     Activity in a cargo journey

HandlingEventFactory            domain.model.handling                @ApplicationScoped          Creates event after checking cargo,
voyage, and location

Jpa*Repository (four classes)   infrastructure.persistence.jpa       @ApplicationScoped,         JPA implementations of domain
@PersistenceContext         repository interfaces

JmsApplicationEvents            infrastructure.messaging.jms         @ApplicationScoped,         Publishes application events to JMS
@Resource                   queues

*Consumer (five classes)        infrastructure.messaging.jms         @MessageDriven              Receives JMS messages and calls
application services

ExternalRoutingService          infrastructure.routing               @Stateless                  REST client of pathfinder

GraphTraversalService           org.eclipse.pathfinder.api           @Stateless,                 Graph traversal REST service
@Path("/graph-traversal")   
GraphDao                        org.eclipse.pathfinder.internal      @ApplicationScoped          Graph data access
6. Current Compilation Issue and Refactoring State
   6.1 Reported IDE errors
   The supplied IDE inspection reports errors in
   EventLineParserTest.java:
- Missing class: cannot resolve symbol EventLineParser (reported
  at line 10).
- Missing method: cannot resolve parse(String) (reported at
  lines 18, 25, 32, 41, 50, and 59).
  6.2 Interpretation
  The parsing logic is currently described as residing in
  EventItemReader.parseLine(). The test refers to a dedicated
  EventLineParser and its parse(String) method, but that production
  API is not available to the test under the referenced name/package.

This is consistent with an incomplete or partially applied extraction.
Possible causes:
1. EventLineParser has not been created.
2. It exists in another package and the test import is incorrect.
3. It exists but does not expose the expected parse(String) method.
4. Production and test code are at different stages of the refactoring.
   The IDE error alone does not establish which cause is correct.
   Inspect the working tree before changing code.
   6.3 Verification sequence
1. Open EventItemReader and locate parseLine(String).
2. Open EventLineParserTest; inspect its package, imports,
   constructor usage, and expected return type.
3. Decide the intended parser package and API, then make production and
   test code agree.
4. Keep file I/O, checkpointing, and file deletion in the batch reader.
   Keep line-to-object conversion in the parser if that is the selected
   extraction boundary.
5. Characterize valid lines, malformed field counts, invalid dates,
   invalid identifiers, unknown event types, optional voyage values,
   and trailing empty fields.
6. Run tests and compile the application after resolving the class/API
   mismatch.
   Do not modify the test merely to silence the error without deciding the
   intended parser contract.
7. Annotation Usage --- Evidence Checklist
   The supplied source review and IDE evidence identify these Jakarta EE /
   CDI patterns:
- @Entity marks persistent entities; EntityManager is used by
  persistence implementations.
- @Named is used for CDI/JSF naming; @NamedQuery is used where
  declared in the persistence model.
- @Singleton, @Stateless, and @ApplicationScoped are used by
  application components, repositories, and facades according to their
  declared scopes.
- @Resource is used for JMS resource/queue injection.
- @MessageDriven marks the five identified JMS consumers.
- @Dependent is used by batch readers, writers, and listeners.
- @ObservesAsync is used by the SSE service to observe asynchronous
  cargo-update events.
- @Schedule is used by UploadDirectoryScanner to trigger periodic
  batch processing.
- @PersistenceContext injects the persistence context into JPA
  repository implementations.
  Verification caution: annotation correctness is contextual. Preserve
  container-managed lifecycle, transaction boundaries, asynchronous event
  behavior, and batch checkpoint semantics when refactoring. The presence
  of an annotation does not by itself prove that a class has the right
  responsibility or dependency direction.
8. Architectural Risks and Refactoring Candidates
   Candidate                     Current evidence                     What to investigate
   Application-to-interface      ApplicationEvents imports          Move the event/command
   dependency                    HandlingEventRegistrationAttempt   contract to an
   from interfaces.handling           inward-facing package
   that application and
   adapters can depend on

Infrastructure-to-interface   JMS publisher/consumer use           Align the JMS message
dependency                    HandlingEventRegistrationAttempt   contract with the
from interfaces                      application-facing
contract

Interface-to-infrastructure   SSE endpoint imports @CargoUpdated Move the
dependency                    from infrastructure                  qualifier/event
contract to a neutral
inward-facing package
or use an application
event abstraction

Facade repository access      DefaultBookingServiceFacade        Decide whether
directly injects repositories for    application query
reads                                services should own
these use cases

Batch reader responsibility   EventItemReader handles file       Extract/test parsing
discovery, lifecycle, checkpointing, separately while
reading, and parsing                 retaining batch
lifecycle behavior in
the reader

File failure documentation    Javadoc says failed files are moved; Reconcile documentation
implementation writes failed lines   and behavior after
and deletes processed files          confirming the intended
contract

Asynchronous event flow       Batch writer publishes JMS; consumer Preserve delivery,
registers the event later            retry, transaction, and
failure behavior
Parser/test mismatch          Test references unavailable          Resolve the intended
EventLineParser.parse(String)      production API and test
contract
8.1 Refactoring constraints
- Preserve public JSF bean property names and navigation outcomes.
- Preserve REST endpoint paths and request/response contracts.
- Preserve JMS destination names and message payload semantics unless
  performing a coordinated migration.
- Preserve batch job names, step configuration, chunk size, skip
  behavior, checkpointing, archive behavior, and failed-line behavior.
- Preserve transaction boundaries and container-managed lifecycle
  annotations unless a deliberate change is supported by tests and
  deployment configuration.
- Preserve the asynchronous handling-event registration pipeline.
- Add characterization tests before changing duplicated or distributed
  behavior.
9. End-to-End Responsibilities at a Glance
   Booking
- Presentation: Booking receives the JSF action.
- Facade: DefaultBookingServiceFacade translates UI-oriented
  arguments and delegates.
- Application use case: DefaultBookingService coordinates ID
  generation, location lookup, route specification creation, cargo
  construction, and persistence.
- Domain: Cargo and RouteSpecification represent business
  concepts and delivery state.
- Persistence: JpaCargoRepository stores cargo and fires the
  cargo-updated CDI event.
- Live update: RealtimeCargoTrackingService publishes the update
  through SSE.
  Handling-event import
- Scheduling: UploadDirectoryScanner starts the Jakarta Batch
  job.
- Batch input: EventItemReader reads and parses lines.
- Batch output: EventItemWriter publishes registration attempts
  and archives processed attempts.
- Messaging: JmsApplicationEvents sends the attempt to the JMS
  queue.
- Consumer: HandlingEventRegistrationAttemptConsumer invokes the
  application service.
- Application: DefaultHandlingEventService creates/stores the
  event and publishes a cargo-handled event.
- Domain factory: HandlingEventFactory resolves cargo, optional
  voyage, and location and constructs the event.
- Inspection: DefaultCargoInspectionService recalculates
  delivery progress and publishes relevant events.
- Live update: cargo persistence triggers @CargoUpdated,
  observed by the SSE endpoint.
10. Scope and Accuracy Notes
- This document describes the current architecture, not a proposed
  target architecture.
- The package dependency table records import relationships and
  examples. Runtime interactions (CDI events, JMS, REST, and container
  callbacks) are shown separately because not every runtime dependency
  appears as a direct Java import.
- The compilation issue in Section 6 is reported from the supplied IDE
  inspection and should be rechecked against the current working tree.
- This document does not claim that every production Java file has
  individually been proven to contain a design smell. The refactoring
  candidates are concrete observations from the reviewed classes and
  flows.
- Keep architecture-current.drawio in the same directory as this
  Markdown file so the relative link works.