package de.saar.minecraft.woz;

import de.saar.minecraft.architect.ArchitectGrpc;
import de.saar.minecraft.architect.ArchitectInformation;
import de.saar.minecraft.shared.*;
import de.saar.minecraft.shared.Void;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

/**
 * A server that provides access to one WOZArchitect
 */
public class WOZArchitectServer {

    private Server server;
    private int port;
    private WOZListener listener;
    private WOZArchitect arch;

    private static Logger logger = LogManager.getLogger(WOZArchitectServer.class);

    public WOZArchitectServer(int port, WOZListener listener) {
        this.listener = listener;
        this.port = port;
    }

    public void start() throws IOException {
        server = ServerBuilder.forPort(port)
                .addService(new ArchitectImpl())
                .build()
                .start();

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                WOZArchitectServer.this.stop();
            }
        });

        System.err.printf("Architect server running on port %d.\n", port);
    }

    public void stop() {
        if (server != null) {
            server.shutdown();
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }


    private class ArchitectImpl extends ArchitectGrpc.ArchitectImplBase {

        public void hello(Void request, StreamObserver<ArchitectInformation> responseObserver) {
            WOZArchitect arch = new WOZArchitect(1000, listener);

            responseObserver.onNext(ArchitectInformation.newBuilder().setInfo(arch.getArchitectInformation()).build());
            responseObserver.onCompleted();
        }


        public void startGame(WorldSelectMessage request, StreamObserver<Void> responseObserver) {
            if (arch == null) {
                arch = new WOZArchitect(5000, listener);
            }
            arch.initialize(request);

            responseObserver.onNext(Void.newBuilder().build());
            responseObserver.onCompleted();

            System.err.printf("architect for id %d: %s\n", request.getGameId(), arch);
        }


        public void endGame(GameId request, StreamObserver<Void> responseObserver) {
            System.err.printf("architect for id %d finished\n", request.getId());
            arch = null;
            responseObserver.onNext(Void.newBuilder().build());
            responseObserver.onCompleted();
        }

        public void handleStatusInformation(StatusMessage request, StreamObserver<TextMessage> responseObserver) {
            arch.handleStatusInformation(request, responseObserver);
        }


        public void handleBlockPlaced(BlockPlacedMessage request, StreamObserver<TextMessage> responseObserver){
            arch.handleBlockPlaced(request, responseObserver);
        }

        public void handleBlockDestroyed(BlockDestroyedMessage request, StreamObserver<TextMessage> responseObserver) {
            arch.handleBlockDestroyed(request, responseObserver);
        }
    }
}
