package com.crabit.backend.recommendation;

import com.crabit.backend.simulation.SimulationDomainRuntime;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import tools.jackson.databind.ObjectMapper;

/** Local simulation-only ranking probe. Does not persist a feed page or collect behavior. */
public final class SimulationFeedExecution {
    private SimulationFeedExecution() {}
    public record Result(String mode, boolean pythonInvoked, FeedRankingModels.Request request,
                         Optional<FeedRankingModels.Result> ranking) {}

    /** Call inside the synchronous simulation clock step. Output must be a new directory. */
    public static Result execute(SimulationDomainRuntime.Services services, String dataset,
            UUID viewer, UUID academy, UUID requestId, UUID contextId,
            URI endpoint, String credential, Path output) throws IOException {
        validateEndpoint(endpoint);
        var classifier=services.service(FeedCategoryClassifier.class);
        var settings=new FeedRankingSettings(endpoint.toString(),credential,classifier.version(),classifier);
        Long owners=services.jdbc().queryForObject("""
            SELECT count(*) FROM demo_simulation_account x
            JOIN demo_simulation_dataset d ON d.dataset_id=x.dataset_id
            JOIN card_balance_account a ON a.id=x.account_id
            JOIN academy_membership m ON m.student_id=a.student_id AND m.academy_id=a.academy_id AND m.left_at IS NULL
            WHERE x.dataset_id=? AND d.state='BUILDING' AND a.student_id=? AND a.academy_id=?
              AND a.closed_at IS NULL AND a.opened_at<=CURRENT_TIMESTAMP
              AND d.starts_at<=CURRENT_TIMESTAMP AND d.ends_at>CURRENT_TIMESTAMP
            """,Long.class,dataset,viewer,academy);
        require(Objects.equals(owners,1L),"FEED_SIMULATION_DATASET_OWNER");
        Objects.requireNonNull(requestId);Objects.requireNonNull(contextId);
        var json=services.service(ObjectMapper.class);
        // Directory collision fails before assembly or any HTTP request.
        Files.createDirectory(output);
        try(var recorder=new RecordingClient(output,json)) {
            var deadline=FeedRankingDeadline.start();
            FeedRankingModels.Request request;
            try {
                request=services.service(FeedRankingRequestAssembler.class).assemble(requestId,contextId,viewer,academy,
                    services.clock().instant(),deadline);
            } catch(FeedRankingRequestAssembler.DeadlineExceeded failure) {
                Files.writeString(output.resolve("result.json"),json.writeValueAsString(Map.of(
                    "mode","LATEST","pythonInvoked",false,"reason","ASSEMBLY_DEADLINE")),StandardOpenOption.CREATE_NEW);
                throw failure;
            }
            Files.write(output.resolve("request.json"),json.writeValueAsBytes(request),StandardOpenOption.CREATE_NEW);
            var result=request.candidates().isEmpty()?Optional.<FeedRankingModels.Result>empty():
                new FeedRankingClient(settings,json,recorder).rank(request,deadline);
            if(recorder.failure!=null) throw new IOException("FEED_RAW_EVIDENCE_WRITE_FAILED",recorder.failure);
            String mode=result.isPresent()?"RECOMMENDED":"LATEST";
            var summary=new LinkedHashMap<String,Object>();summary.put("mode",mode);summary.put("pythonInvoked",recorder.invoked);
            summary.put("ranking",result.orElse(null));summary.put("candidateCount",request.candidates().size());
            Files.writeString(output.resolve("result.json"),json.writeValueAsString(summary),StandardOpenOption.CREATE_NEW);
            // Verify after ranking so raw capture/oracle work cannot consume the HTTP deadline.
            // Empty candidate requests are checked too, without inventing an HTTP exchange.
            var source=SimulationFeedInputVerifier.capture(services.jdbc());
            Files.write(output.resolve("input-source.json"),json.writeValueAsBytes(source),StandardOpenOption.CREATE_NEW);
            var verified=SimulationFeedInputVerifier.verify(json.valueToTree(request),source);
            Files.write(output.resolve("input-verification.json"),json.writeValueAsBytes(verified),StandardOpenOption.CREATE_NEW);
            if(result.isPresent()) {
                var composition=SimulationFeedCompositionVerifier.verify(json.valueToTree(request),json.readTree(Files.readAllBytes(output.resolve("response.json"))));
                Files.write(output.resolve("composition-verification.json"),json.writeValueAsBytes(composition),StandardOpenOption.CREATE_NEW);
                var ranking=SimulationFeedRankingVerifier.verify(json.valueToTree(request),json.readTree(Files.readAllBytes(output.resolve("response.json"))));
                Files.write(output.resolve("ranking-verification.json"),json.writeValueAsBytes(ranking),StandardOpenOption.CREATE_NEW);
            }
            return new Result(mode,recorder.invoked,request,result);
        }
    }
    public static void validateEndpoint(URI endpoint) {
        require(endpoint!=null && "http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost())
            && endpoint.getPort()>0 && endpoint.getPort()<=65535 && endpoint.getUserInfo()==null
            && endpoint.getRawQuery()==null && endpoint.getFragment()==null
            && "/internal/v1/feed-rankings".equals(endpoint.getRawPath()),"FEED_LOCAL_ENDPOINT_REQUIRED");
    }
    private static void require(boolean value,String code) {if(!value)throw new IllegalArgumentException(code);}

    private static final class RecordingClient extends HttpClient {
        private final HttpClient delegate=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).followRedirects(Redirect.NEVER).build();
        private final Path output; private final ObjectMapper json; private volatile IOException failure; private volatile boolean invoked;
        RecordingClient(Path output,ObjectMapper json) {this.output=output;this.json=json;}
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler) {
            validateEndpoint(request.uri());
            if(invoked) throw new IllegalStateException("FEED_MULTIPLE_HTTP_ATTEMPTS");
            invoked=true;
            return delegate.sendAsync(request,handler).thenApply(response->{
                if(!(response.body() instanceof byte[] bytes))throw new IllegalStateException("FEED_EXPECTED_BYTE_RESPONSE");
                try {
                    Files.write(output.resolve("response.json"),bytes,StandardOpenOption.CREATE_NEW);
                    Files.writeString(output.resolve("http.json"),json.writeValueAsString(Map.of(
                        "status",response.statusCode(),"contentType",response.headers().firstValue("Content-Type").orElse(""),
                        "requestBytes",request.bodyPublisher().orElseThrow().contentLength(),"responseBytes",bytes.length)),StandardOpenOption.CREATE_NEW);
                } catch(IOException e) {failure=e;throw new UncheckedIOException("FEED_RAW_EVIDENCE_WRITE_FAILED",e);}
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
