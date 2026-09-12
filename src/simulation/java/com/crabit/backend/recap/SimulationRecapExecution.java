package com.crabit.backend.recap;

import com.crabit.backend.simulation.SimulationDomainRuntime;
import com.crabit.backend.simulation.SimulationRecapPreparation;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import tools.jackson.databind.ObjectMapper;

/** Simulation-only, synchronous completion using the production transport and coordinator. */
public final class SimulationRecapExecution {
    private SimulationRecapExecution() {}
    public record Result(UUID generationId,String state,String viewJson,String internalMetricsJson,boolean pythonInvoked) {}

    /** Call inside executeAt. The output directory must be new, owned by this local replay. */
    public static Result complete(SimulationDomainRuntime.Services services,String dataset,
            SimulationRecapPreparation.Prepared prepared,URI endpoint,String credential,Path output) throws IOException {
        validateEndpoint(endpoint);
        var json=services.service(ObjectMapper.class);
        var settings=new RecapServiceSettings(endpoint.toString(),credential);
        var repository=services.service(RecapGenerationRepository.class);
        var stored=repository.findById(prepared.generationId()).orElseThrow();
        require(Objects.equals(stored.requestJson(),prepared.requestJson()) && Objects.equals(stored.inputDigest(),prepared.inputDigest())
            && stored.generationVersion()==prepared.generationVersion(),"RECAP_FROZEN_INPUT_CONFLICT");
        Long owners=services.jdbc().queryForObject("""
            SELECT count(*) FROM demo_simulation_account x JOIN demo_simulation_dataset d ON d.dataset_id=x.dataset_id
            JOIN card_balance_account a ON a.id=x.account_id
            WHERE x.dataset_id=? AND x.account_id=? AND d.state='BUILDING' AND a.student_id=? AND a.academy_id=? AND a.closed_at IS NULL
            """,Long.class,dataset,stored.accountId(),stored.studentId(),stored.academyId());
        require(Objects.equals(owners,1L),"RECAP_SIMULATION_DATASET_OWNER");
        if(stored.state()==RecapGenerationState.SUCCEEDED || stored.state()==RecapGenerationState.NOT_ELIGIBLE)
            return result(stored,false);
        require(stored.state()==RecapGenerationState.PENDING,"RECAP_EXECUTION_NOT_PENDING");
        // The isolated runtime serializes preparation and completion. Never claim another generation.
        var now=services.clock().instant();
        Long ready=services.jdbc().queryForObject("""
            SELECT count(*) FROM recap_generation WHERE stage='GENERATION' AND
            (state='PENDING' OR (state='FAILED' AND next_attempt_at<=?) OR (state='RUNNING' AND started_at<=?))
            """,Long.class,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now.minus(Duration.ofMinutes(2))));
        require(Objects.equals(ready,1L),"RECAP_EXECUTION_REQUIRES_SINGLE_READY_GENERATION");
        Files.createDirectory(output);
        Files.writeString(output.resolve("request.json"),stored.requestJson(),StandardOpenOption.CREATE_NEW);
        var coordinator=services.service(RecapGenerationCoordinator.class);
        var claim=coordinator.claim(now).orElseThrow();
        require(claim.id().equals(stored.id()),"RECAP_CLAIM_IDENTITY");
        try(var recorder=new RecordingClient(output,json)) {
            try {
                var response=new RecapPythonClient(settings,json,recorder).generate(claim);
                SimulationRecapResultVerifier.Verified verified;
                try {
                    verified=SimulationRecapResultVerifier.verify(json.readTree(claim.requestJson()),json.readTree(response.viewJson()));
                } catch(IllegalArgumentException invalid) {
                    throw new RecapTransportException(invalid.getMessage(),false);
                }
                Files.writeString(output.resolve("result-verification.json"),json.writeValueAsString(verified),StandardOpenOption.CREATE_NEW);
                coordinator.succeed(claim,response.viewJson(),response.internalMetricsJson(),now);
                var actual=repository.findById(claim.id()).orElseThrow();
                require(actual.state()==RecapGenerationState.SUCCEEDED && actual.currentVersion()
                    && actual.requestJson().equals(claim.requestJson()) && actual.inputDigest().equals(claim.inputDigest())
                    && actual.viewJson().equals(response.viewJson()) && actual.internalMetricsJson().equals(response.internalMetricsJson())
                    && now.equals(actual.generatedAt()),"RECAP_PERSISTENCE_MISMATCH");
                Files.writeString(output.resolve("persisted.json"),json.writeValueAsString(Map.of(
                    "generationId",actual.id(),"state",actual.state(),"generatedAt",actual.generatedAt(),
                    "inputDigest",actual.inputDigest(),"view",json.readTree(actual.viewJson()),
                    "internalMetrics",json.readTree(actual.internalMetricsJson()))),StandardOpenOption.CREATE_NEW);
                return result(actual,true);
            } catch(RecapTransportException failure) {
                // Preserve the production failure classification. No automatic retry or invented view.
                coordinator.fail(claim,failure.code(),failure.retryable(),now);
                Files.writeString(output.resolve("failure.json"),json.writeValueAsString(Map.of(
                    "code",failure.code(),"retryable",failure.retryable(),"generationId",claim.id())),StandardOpenOption.CREATE_NEW);
                throw failure;
            }
        }
    }
    public static void validateEndpoint(URI endpoint) {
        require(endpoint!=null && "http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())
            && endpoint.getPort()>0 && endpoint.getPort()<=65535 && endpoint.getUserInfo()==null && endpoint.getRawQuery()==null && endpoint.getFragment()==null
            && "/internal/v1/recap-generations".equals(endpoint.getRawPath()),"RECAP_LOCAL_ENDPOINT_REQUIRED");
    }
    private static Result result(RecapGeneration g,boolean invoked) {return new Result(g.id(),g.state().name(),g.viewJson(),g.internalMetricsJson(),invoked);}
    private static void require(boolean value,String code) {if(!value)throw new IllegalArgumentException(code);}

    /** Records the bounded bytes accepted by the production HTTP client's body subscriber, before validation. */
    private static final class RecordingClient extends HttpClient {
        private final HttpClient delegate=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(Redirect.NEVER).build();
        private final Path output; private final ObjectMapper json;
        RecordingClient(Path output,ObjectMapper json) {this.output=output;this.json=json;}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler) {
            validateEndpoint(request.uri());
            return delegate.sendAsync(request,handler).thenApply(response->{
                if(!(response.body() instanceof byte[] bytes))throw new IllegalStateException("RECAP_EXPECTED_BYTE_RESPONSE");
                try {
                    Files.write(output.resolve("response.json"),bytes,StandardOpenOption.CREATE_NEW);
                    Files.writeString(output.resolve("http.json"),json.writeValueAsString(Map.of(
                        "status",response.statusCode(),"contentType",response.headers().firstValue("Content-Type").orElse(""),
                        "requestBytes",request.bodyPublisher().orElseThrow().contentLength(),"responseBytes",bytes.length)),StandardOpenOption.CREATE_NEW);
                } catch(IOException e) {throw new UncheckedIOException("RECAP_RAW_EVIDENCE_WRITE_FAILED",e);}
                return response;
            });
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p) {throw new UnsupportedOperationException();}
        @Override public <T> HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h) {throw new UnsupportedOperationException();}
        @Override public Optional<CookieHandler> cookieHandler(){return delegate.cookieHandler();}
        @Override public Optional<Duration> connectTimeout(){return delegate.connectTimeout();}
        @Override public Redirect followRedirects(){return delegate.followRedirects();}
        @Override public Optional<ProxySelector> proxy(){return delegate.proxy();}
        @Override public SSLContext sslContext(){return delegate.sslContext();}
        @Override public SSLParameters sslParameters(){return delegate.sslParameters();}
        @Override public Optional<Authenticator> authenticator(){return delegate.authenticator();}
        @Override public Version version(){return delegate.version();}
        @Override public Optional<Executor> executor(){return delegate.executor();}
        @Override public void close(){delegate.close();}
    }
}
