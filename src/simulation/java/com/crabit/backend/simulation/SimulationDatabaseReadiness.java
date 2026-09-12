package com.crabit.backend.simulation;

import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** Startup-only read probe. Never wrap migrations or domain operations in this retry. */
final class SimulationDatabaseReadiness {
    private SimulationDatabaseReadiness() {}

    static void await(Runnable probe) {
        for (int attempt = 1; ; attempt++) {
            try {
                probe.run();
                return;
            } catch (CannotGetJdbcConnectionException failure) {
                if (attempt == 3 || !transportFailure(failure)) throw failure;
                try {
                    Thread.sleep(200);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    var stopped = new IllegalStateException("SIMULATION_DATABASE_STARTUP_INTERRUPTED", interrupted);
                    stopped.addSuppressed(failure);
                    throw stopped;
                }
            }
        }
    }

    private static boolean transportFailure(Throwable failure) {
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof ConnectException
                    || cause instanceof SocketException) return true;
        }
        return false;
    }
}
