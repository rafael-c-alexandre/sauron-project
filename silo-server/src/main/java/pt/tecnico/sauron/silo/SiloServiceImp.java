package pt.tecnico.sauron.silo;

import io.grpc.stub.StreamObserver;
import pt.tecnico.sauron.silo.api.GossipMessage;
import pt.tecnico.sauron.silo.api.LogRecord;
import pt.tecnico.sauron.silo.api.Operation;
import pt.tecnico.sauron.silo.api.ServerRequestHandler;
import pt.tecnico.sauron.silo.domain.Camera;
import pt.tecnico.sauron.silo.domain.Observation;
import pt.tecnico.sauron.silo.domain.Silo;
import pt.tecnico.sauron.silo.exceptions.*;
import pt.tecnico.sauron.silo.grpc.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.grpc.Status.*;


public class SiloServiceImp extends SiloOperationsServiceGrpc.SiloOperationsServiceImplBase {


    private Silo silo = new Silo();

    private ServerRequestHandler serverRequestHandler;

    private int replicaNumber;

    public SiloServiceImp(Integer repN) {
        this.serverRequestHandler = new ServerRequestHandler(repN);
        this.replicaNumber = repN;
    }


    public void runUpdates(List<LogRecord> logRecords) {

        for (LogRecord logRecord : logRecords) {
            Operation operation = logRecord.getOperation();
            ClientRequest request = operation.getRequest();
            String function = operation.getOperation();


            switch (function) {
                case "CamJoin":
                    camJoinAux(request, null);
                    break;
                case "Report":
                    reportAux(request, null);
                    break;

                case "CtrlClear":
                    ctrlClearAux();
                    break;
                case "CtrlInit":
                    ctrlInitAux();
                    break;
            }
            this.serverRequestHandler.updateReplicaState(logRecord);
        }

    }

    @Override
    public void gossip(GossipRequest request, StreamObserver<GossipResponse> responseObserver) {
        System.out.println("Gossip message Received");
        List<LogRecord> stableUpdates;
        //Build gossip object
        List<LogRecord> lr = new ArrayList<>();
        for (LogRecordsRequest lrr : request.getLogList()) {
            OperationRequest opr = lrr.getOperation();
            lr.add(new LogRecord(lrr.getRepN(),
                    lrr.getTimestampMap(),
                    lrr.getPrevTSMap(),
                    lrr.getId(),
                    new Operation(opr.getOp(), opr.getRequest())));
        }

        //Merge replica log with gossip log
        this.serverRequestHandler.mergeIncomingLog(new GossipMessage(lr, request.getRepTsMap()));

        //Get stable updates
        stableUpdates = this.serverRequestHandler.getStableUpdates();

        //Run stable updates
        runUpdates(stableUpdates);

        // Send a single response through the stream.
        responseObserver.onNext(GossipResponse.newBuilder().build());
        // Notify the client that the operation has been completed.
        responseObserver.onCompleted();
    }


    //CamJoin domain logic
    public void camJoinAux(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {
        try {
            Camera camera = new Camera(request.getCamJoinRequest().getCamName(), request.getCamJoinRequest().getLatitude(), request.getCamJoinRequest().getLongitude());
            silo.addCamera(camera);
        } catch (CameraNameNotUniqueException e) {
            responseObserver.onError(ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException());
        } catch (CameraNameInvalidException |
                CameraNameNullException |
                InvalidCoordinatesException e) {
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }

    }

    @Override
    public void camJoin(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {


        LogRecord logRecord;
        try {
            logRecord = this.serverRequestHandler.processUpdateRequest("CamJoin", request, responseObserver);


            //implements domain logic
            camJoinAux(request, responseObserver);

            //Builds response
            CamJoinResponse response = CamJoinResponse.newBuilder().build();
            ClientResponse clientResponse = ClientResponse.newBuilder().setCamJoinResponse(response).putAllResponseTS(logRecord.getTimestamp()).build();

            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);
            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

            this.serverRequestHandler.addRecordToLog(logRecord);

            this.serverRequestHandler.updateReplicaState(logRecord);

        } catch (DuplicateOperationException e) {
            responseObserver.onError(ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    //Report domain logic
    public void reportAux(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {

        try {
            String camName = request.getReportRequest().getCamName();
            List<ObservationMessage> observationMessages;


            if (silo.checkIfCameraExists(camName)) {

                Camera cam = silo.getCameraByName(camName);

                observationMessages = request.getReportRequest().getObservationList();
                for (ObservationMessage om : observationMessages) {
                    checkType(om.getType());
                    cam.addObservation(new Observation(om.getType()
                            , om.getId()
                            , LocalDateTime.parse(om.getDatetime(), Silo.formatter)
                            , camName
                    ));
                }
            } else {
                throw new NoSuchCameraNameException(camName);
            }

        } catch (NoSuchCameraNameException e) {
            responseObserver.onError(NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (CameraNameNullException |
                InvalidTypeException |
                InvalidIdException |
                InvalidDateException e) {
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }


    }

    @Override
    public void report(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {
        try {

            LogRecord logRecord = this.serverRequestHandler.processUpdateRequest("Report", request, responseObserver);


            //Implements domain logic
            reportAux(request, responseObserver);

            //Builds response
            ReportResponse response = ReportResponse.newBuilder().build();

            ClientResponse clientResponse = ClientResponse.newBuilder().setReportResponse(response).putAllResponseTS(logRecord.getTimestamp()).build();


            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);

            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

            this.serverRequestHandler.addRecordToLog(logRecord);

            this.serverRequestHandler.updateReplicaState(logRecord);


        } catch (DuplicateOperationException e) {
            responseObserver.onError(ALREADY_EXISTS.withDescription(e.getMessage()).asException());
        }
    }


    @Override
    public void camInfo(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {
        try {
            String camName = request.getCamInfoRequest().getCamName();
            Camera camera = silo.getCameraByName(camName);

            CamInfoResponse response = CamInfoResponse.newBuilder()
                    .setLatitude(camera.getLat())
                    .setLongitude(camera.getLog())
                    .build();

            ClientResponse clientResponse = ClientResponse.newBuilder().setCamInfoResponse(response).putAllResponseTS(this.serverRequestHandler.getValueTS()).build();

            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);

            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

        } catch (NoSuchCameraNameException e) {
            responseObserver.onError(NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch (CameraNameNullException e) {
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        }

    }

    @Override
    public void track(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {
        try {


            String type = request.getTrackRequest().getType();
            checkType(type);

            String id = request.getTrackRequest().getId();

            Observation result;

            result = silo.trackObject(type, id);

            //Build Observation Message
            ObservationMessage observationMessage = ObservationMessage.newBuilder()
                    .setId(result.getId())
                    .setType(result.getType())
                    .setDatetime(result.getDateTime().format(Silo.formatter))
                    .setCamName(result.getCamName())
                    .build();

            TrackResponse response = TrackResponse.newBuilder()
                    .setObservation(observationMessage)
                    .build();

            System.out.println("Sending most recent observation of object with id:" + id + " and type:" + type + "...");

            ClientResponse clientResponse = ClientResponse.newBuilder().putAllResponseTS(this.serverRequestHandler.getValueTS()).setTrackResponse(response).build();


            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);

            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

        } catch (InvalidIdException |
                InvalidTypeException e) {
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (NoSuchObjectException e) {
            responseObserver.onError(NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }

    }

    @Override
    public void trackMatch(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {


        try {


            String type = request.getTrackMatchRequest().getType();
            checkType(type);

            String id = request.getTrackMatchRequest().getSubId();
            List<Observation> result;
            TrackMatchResponse.Builder builder = TrackMatchResponse.newBuilder();


            result = silo.trackMatchObject(type, id);

            for (Observation o : result) {
                //Build Observation Message
                ObservationMessage observationMessage = ObservationMessage.newBuilder()
                        .setId(o.getId())
                        .setType(o.getType())
                        .setDatetime(o.getDateTime().format(Silo.formatter))
                        .setCamName(o.getCamName())
                        .build();

                builder.addObservation(observationMessage);
            }


            TrackMatchResponse response = builder.build();

            System.out.println("Sending most recent observations of objects with partialid:" + id + " and type:" + type + "...");

            ClientResponse clientResponse = ClientResponse.newBuilder().putAllResponseTS(this.serverRequestHandler.getValueTS()).setTrackMatchResponse(response).build();


            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);

            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

        } catch (InvalidTypeException |
                InvalidIdException e) {
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (NoSuchObjectException e) {
            responseObserver.onError(NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }

    }


    @Override
    public void trace(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {

        try {

            String type = request.getTraceRequest().getType();
            checkType(type);

            String id = request.getTraceRequest().getId();
            List<Observation> result;
            TraceResponse.Builder builder = TraceResponse.newBuilder();

            result = silo.traceObject(type, id);

            for (Observation o : result) {
                //Build Observation Message
                ObservationMessage observationMessage = ObservationMessage.newBuilder()
                        .setId(o.getId())
                        .setType(o.getType())
                        .setDatetime(o.getDateTime().format(Silo.formatter))
                        .setCamName(o.getCamName())
                        .build();

                builder.addObservation(observationMessage);
            }


            TraceResponse response = builder.build();


            System.out.println("Sending trace path of object with id:" + id + " and type:" + type + "...");

            ClientResponse clientResponse = ClientResponse.newBuilder().putAllResponseTS(this.serverRequestHandler.getValueTS()).setTraceResponse(response).build();

            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);

            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

        } catch (InvalidIdException |
                InvalidTypeException e) {
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch (NoSuchObjectException e) {
            responseObserver.onError(NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }

    }


    @Override
    public void ctrlPing(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {
        String inputText = request.getPingRequest().getInputCommand();

        if (inputText == null || inputText.isBlank()) {
            responseObserver.onError(INVALID_ARGUMENT
                    .withDescription("Input cannot be empty!").asRuntimeException());
            return;
        }

        String output = "Hello " + inputText + "!\n" + "The server is running!";
        PingResponse response = PingResponse.newBuilder().setOutputText(output).build();
        System.out.println("Ping request received");

        ClientResponse clientResponse = ClientResponse.newBuilder().putAllResponseTS(this.serverRequestHandler.getValueTS()).setPingResponse(response).build();


        // Send a single response through the stream.
        responseObserver.onNext(clientResponse);
        // Notify the client that the operation has been completed.
        responseObserver.onCompleted();
    }

    public void ctrlClearAux() {
        //Clears server info
        this.silo = new Silo();
        this.serverRequestHandler = new ServerRequestHandler(this.replicaNumber);

        System.out.println("System state cleared");
    }

    @Override
    public void ctrlClear(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {

        LogRecord logRecord = null;
        try {
            logRecord = this.serverRequestHandler.processUpdateRequest("CtrlClear", request, responseObserver);


            //silo.clearData();
            ClearResponse response = ClearResponse.newBuilder().build();

            //Clears server info
            ctrlClearAux();

            ClientResponse clientResponse = ClientResponse.newBuilder().setClearResponse(response).putAllResponseTS(logRecord.getTimestamp()).build();

            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);
            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

            this.serverRequestHandler.addRecordToLog(logRecord);

            this.serverRequestHandler.updateReplicaState(logRecord);
        } catch (DuplicateOperationException e) {
            responseObserver.onError(ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException());
        }

    }

    public void ctrlInitAux() {

    }

    @Override
    public void ctrlInit(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {

        LogRecord logRecord = null;
        try {
            logRecord = this.serverRequestHandler.processUpdateRequest("CtrlInit", request, responseObserver);


            ctrlInitAux();

            InitResponse response = InitResponse.newBuilder().build();

            ClientResponse clientResponse = ClientResponse.newBuilder().setInitResponse(response).putAllResponseTS(logRecord.getTimestamp()).build();

            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);
            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

            this.serverRequestHandler.addRecordToLog(logRecord);

            this.serverRequestHandler.updateReplicaState(logRecord);
        } catch (DuplicateOperationException e) {
            responseObserver.onError(ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException());
        }

    }

    public Silo getSilo() {
        return silo;
    }

    public void setSilo(Silo silo) {
        this.silo = silo;
    }

    //Checks if type is valid
    private void checkType(String type) throws InvalidTypeException {

        if (type == null || type.strip().length() == 0) {
            throw new InvalidTypeException();
        }
        if (!type.equals("PERSON") &&
                !type.equals("CAR")) {
            throw new InvalidTypeException(type);
        }
    }

    public GossipRequest buildGossipRequest() {
        return this.serverRequestHandler.buildGossipRequest();
    }

    //checks if a happens before b
    private boolean happensBefore(Map<Integer, Integer> a, Map<Integer, Integer> b) {

        for (Map.Entry<Integer, Integer> entryA : a.entrySet()) {
            Integer valueB = b.getOrDefault(entryA.getKey(), 0);
            if (entryA.getValue() > valueB) return false;
        }
        return true;
    }

}
