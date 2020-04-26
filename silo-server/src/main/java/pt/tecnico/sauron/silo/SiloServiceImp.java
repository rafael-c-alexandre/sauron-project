package pt.tecnico.sauron.silo;

import io.grpc.stub.StreamObserver;
import pt.tecnico.sauron.silo.domain.Camera;
import pt.tecnico.sauron.silo.domain.LogRecords;
import pt.tecnico.sauron.silo.domain.Observation;
import pt.tecnico.sauron.silo.domain.Silo;
import pt.tecnico.sauron.silo.exceptions.*;
import pt.tecnico.sauron.silo.grpc.*;

import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static io.grpc.Status.ALREADY_EXISTS;
import static io.grpc.Status.INVALID_ARGUMENT;
import static io.grpc.Status.NOT_FOUND;


public class SiloServiceImp extends SiloOperationsServiceGrpc.SiloOperationsServiceImplBase {

    private Integer replicaNumber;
    private Silo silo = new Silo();

    private Map<Integer, Integer> replicaTS = new ConcurrentHashMap<Integer, Integer>();

    private List<LogRecords> updateLog = new CopyOnWriteArrayList<>();

    private Map<Integer, Integer> valueTS = new ConcurrentHashMap<Integer, Integer>();

    private List<String> executedOpsTable = new CopyOnWriteArrayList<>();

    private Map<Integer, Integer> prevTS = new ConcurrentHashMap<Integer, Integer>();

    private List<ClientRequest> pendingQueries = new CopyOnWriteArrayList<>();


    public SiloServiceImp(Integer repN) {
        this.replicaNumber = repN;
    }

    @Override
    public void camJoin(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {

        try {

            Camera camera = new Camera(request.getCamJoinRequest().getCamName(), request.getCamJoinRequest().getLatitude(), request.getCamJoinRequest().getLongitude());
            silo.addCamera(camera);
            CamJoinResponse response = CamJoinResponse.newBuilder().build();

            ClientResponse clientResponse = ClientResponse.newBuilder().setCamJoinResponse(response).build();

            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);
            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

        } catch ( CameraNameNotUniqueException e){
            responseObserver.onError(ALREADY_EXISTS.withDescription(e.getMessage()).asRuntimeException());
        } catch ( CameraNameInvalidException  |
                  CameraNameNullException     |
                  InvalidCoordinatesException e ) {
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
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

            ClientResponse clientResponse = ClientResponse.newBuilder().setCamInfoResponse(response).build();

            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);

            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

        } catch ( NoSuchCameraNameException e ){
            responseObserver.onError(NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch ( CameraNameNullException e ){
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

            System.out.println("Sending most recent observation of object with id:" + id + " and type:" +type + "..." );

            ClientResponse clientResponse = ClientResponse.newBuilder().setTrackResponse(response).build();


            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);

            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

        } catch( InvalidIdException   |
                 InvalidTypeException e ){
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch ( NoSuchObjectException e ){
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

            System.out.println("Sending most recent observations of objects with partialid:" + id + " and type:" +type + "...");

            ClientResponse clientResponse = ClientResponse.newBuilder().setTrackMatchResponse(response).build();


            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);

            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

        } catch ( InvalidTypeException   |
                  InvalidIdException     e ){
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch ( NoSuchObjectException e ){
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


            System.out.println("Sending trace path of object with id:" + id + " and type:" +type + "...");

            ClientResponse clientResponse = ClientResponse.newBuilder().setTraceResponse(response).build();

            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);

            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

        } catch ( InvalidIdException   |
                  InvalidTypeException e ){
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
        } catch ( NoSuchObjectException e){
            responseObserver.onError(NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        }


    }

    @Override
    public void report(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {
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
            }
            else{
                responseObserver.onError(NOT_FOUND.withDescription("No such camera").asRuntimeException());
                return;
            }

            ReportResponse response = ReportResponse.newBuilder().build();

            ClientResponse clientResponse = ClientResponse.newBuilder().setReportResponse(response).build();


            // Send a single response through the stream.
            responseObserver.onNext(clientResponse);

            // Notify the client that the operation has been completed.
            responseObserver.onCompleted();

        } catch ( NoSuchCameraNameException e ){
            responseObserver.onError(NOT_FOUND.withDescription(e.getMessage()).asRuntimeException());
        } catch ( CameraNameNullException  |
                  InvalidTypeException     |
                  InvalidIdException       |
                  InvalidDateException     e){
            responseObserver.onError(INVALID_ARGUMENT.withDescription(e.getMessage()).asRuntimeException());
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

        ClientResponse clientResponse = ClientResponse.newBuilder().setPingResponse(response).build();


        // Send a single response through the stream.
        responseObserver.onNext(clientResponse);
        // Notify the client that the operation has been completed.
        responseObserver.onCompleted();
    }

    @Override
    public void ctrlClear(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {

        //silo.clearData();
        ClearResponse response = ClearResponse.newBuilder().build();

        //Clears server info
        silo = new Silo();
        System.out.println("System state cleared");

        ClientResponse clientResponse = ClientResponse.newBuilder().setClearResponse(response).build();

        // Send a single response through the stream.
        responseObserver.onNext(clientResponse);
        // Notify the client that the operation has been completed.
        responseObserver.onCompleted();
    }

    @Override
    public void ctrlInit(ClientRequest request, StreamObserver<ClientResponse> responseObserver) {

        InitResponse response = InitResponse.newBuilder().build();

        ClientResponse clientResponse = ClientResponse.newBuilder().setInitResponse(response).build();


        // Send a single response through the stream.
        responseObserver.onNext(clientResponse);
        // Notify the client that the operation has been completed.
        responseObserver.onCompleted();


    }

    public synchronized void updateSiloState(Integer replicaNumber, ClientRequest request) {

        if (isInExecutedUpdates(request.getOpId())) {}

        increaseReplicaTS(replicaNumber);

        Map<Integer,Integer> updateTS = this.replicaTS;g
        LogRecords logRecord = new LogRecords(replicaNumber, updateTS,request, this.prevTS,request.getOpId());

        updateLog.add(logRecord);


    }

    public synchronized boolean processReadRequest(ClientRequest e) {
        if (happensBefore(this.prevTS, this.valueTS)) {
            return true;
        }
        else {
            this.pendingQueries.add(e);
            return false;
        }
    }

    public synchronized void increaseReplicaTS(Integer replicaNumber) {
        this.replicaTS.merge(replicaNumber, 1, Integer::sum);
    }


    //checks if update has already been done
    private synchronized boolean isInExecutedUpdates(String operationID) {
        return !this.executedOpsTable.contains(operationID);
    }

    //checks if a happens before b
    private boolean happensBefore(Map<Integer, Integer> a, Map<Integer, Integer> b) {

        boolean isBefore = true;
        for (Map.Entry<Integer, Integer> entryA : a.entrySet()) {
            Integer valueB = b.get(entryA.getKey());
            if ( entryA.getValue() > valueB) isBefore = false;
        }
        return isBefore;
    }

    //Checks if type is valid
    private void checkType(String type){

          if(type == null || type.strip().length() == 0) {
              throw new InvalidTypeException();
          }
          if( !type.equals("PERSON") &&
              !type.equals("CAR") ){
              throw new InvalidTypeException(type);
        }
    }

    public Integer getReplicaNumber() {
        return replicaNumber;
    }

    public void setReplicaNumber(Integer replicaNumber) {
        this.replicaNumber = replicaNumber;
    }

    public Silo getSilo() {
        return silo;
    }

    public void setSilo(Silo silo) {
        this.silo = silo;
    }

    public Map<Integer, Integer> getReplicaTS() {
        return replicaTS;
    }

    public void setReplicaTS(Map<Integer, Integer> replicaTS) {
        this.replicaTS = replicaTS;
    }

    public List<LogRecords> getUpdateLog() {
        return updateLog;
    }

    public void setUpdateLog(List<LogRecords> updateLog) {
        this.updateLog = updateLog;
    }

    public Map<Integer, Integer> getValueTS() {
        return valueTS;
    }

    public void setValueTS(Map<Integer, Integer> valueTS) {
        this.valueTS = valueTS;
    }

    public List<String> getExecutedOpsTable() {
        return executedOpsTable;
    }

    public void setExecutedOpsTable(List<String> executedOpsTable) {
        this.executedOpsTable = executedOpsTable;
    }

    public Map<Integer, Integer> getPrevTS() {
        return prevTS;
    }

    public void setPrevTS(Map<Integer, Integer> prevTS) {
        this.prevTS = prevTS;
    }
}
