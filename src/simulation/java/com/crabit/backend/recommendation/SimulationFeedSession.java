package com.crabit.backend.recommendation;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import tools.jackson.databind.ObjectMapper;

/** Per-command recording transport for the actual production feed pipeline, local simulation only. */
public final class SimulationFeedSession extends HttpClient {
    private final URI endpoint;
    private final String credential;
    private final Path root;
    private final HttpClient delegate;
    private final Duration deadlineBudget;
    private ObjectMapper json;
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    private Capture active;
    private boolean closed;
    private static final class Capture {
        final Path output; boolean invoked, sealed, responseCaptured; volatile IOException failure;
        Capture(Path output){this.output=output;}
    }
    public SimulationFeedSession(URI endpoint,String credential,Path root) throws IOException {
        this(endpoint,credential,root,Duration.ofMillis(500));
    }
    public SimulationFeedSession(URI endpoint,String credential,Path root,Duration deadlineBudget) throws IOException {
        SimulationFeedExecution.validateEndpoint(endpoint);
        if(credential==null || credential.isBlank())throw new IllegalArgumentException("FEED_CREDENTIAL_REQUIRED");
        if(Files.isSymbolicLink(root) || !Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IllegalArgumentException("FEED_EVIDENCE_ROOT_REQUIRED");
        this.endpoint=endpoint;this.credential=credential;this.root=root.toRealPath();
        if(!Set.of(Duration.ofMillis(500),Duration.ofSeconds(30)).contains(deadlineBudget))throw new IllegalArgumentException("FEED_REPLAY_BUDGET");
        this.deadlineBudget=deadlineBudget;
        delegate=HttpClient.newBuilder().connectTimeout(deadlineBudget).followRedirects(Redirect.NEVER).build();
    }
    public FeedRankingClient client(FeedCategoryClassifier classifier,ObjectMapper json,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        if(this.json!=null)throw new IllegalStateException("FEED_SESSION_ALREADY_BOUND");
        this.json=json;this.jdbc=jdbc;
        return new FeedRankingClient(new FeedRankingSettings(endpoint.toString(),credential,classifier.version(),classifier),json,this,
            () -> new FeedRankingDeadline(System::nanoTime,deadlineBudget));
    }
    public synchronized void begin(long sequence) throws IOException {
        if(closed || active!=null || json==null || sequence<1)throw new IllegalStateException("FEED_CAPTURE_STATE");
        active=new Capture(Files.createDirectory(root.resolve("event-"+sequence)));
    }
    public synchronized void finish(Object page) throws IOException {
        Capture capture=active;
        if(capture==null)throw new IllegalStateException("FEED_CAPTURE_NOT_ACTIVE");
        active=null;
        synchronized(capture) {
        capture.sealed=true;
        if(capture.failure!=null)throw new IOException("FEED_RAW_EVIDENCE_WRITE_FAILED",capture.failure);
        var summary=new LinkedHashMap<String,Object>();summary.put("pythonInvoked",capture.invoked);summary.put("responseCaptured",capture.responseCaptured);summary.put("page",page);
        Files.write(capture.output.resolve("page.json"),json.writeValueAsBytes(summary),StandardOpenOption.CREATE_NEW);
        if(page!=null) {
            var pageNode=json.valueToTree(page);
            var pageSource=SimulationFeedPageVerifier.capture(jdbc,pageNode);
            Files.write(capture.output.resolve("page-source.json"),json.writeValueAsBytes(pageSource),StandardOpenOption.CREATE_NEW);
            var pageVerified=SimulationFeedPageVerifier.verify(pageNode,pageSource);
            Files.write(capture.output.resolve("page-verification.json"),json.writeValueAsBytes(pageVerified),StandardOpenOption.CREATE_NEW);
        }
        if(Files.exists(capture.output.resolve("request.json"))) {
            var source=SimulationFeedInputVerifier.capture(jdbc);
            Files.write(capture.output.resolve("input-source.json"),json.writeValueAsBytes(source),StandardOpenOption.CREATE_NEW);
            var verified=SimulationFeedInputVerifier.verify(json.readTree(Files.readAllBytes(capture.output.resolve("request.json"))),source);
            Files.write(capture.output.resolve("input-verification.json"),json.writeValueAsBytes(verified),StandardOpenOption.CREATE_NEW);
            if(capture.responseCaptured && page!=null && "RECOMMENDATION".equals(json.valueToTree(page).path("sortSource").asString())) {
                var composition=SimulationFeedCompositionVerifier.verify(json.readTree(Files.readAllBytes(capture.output.resolve("request.json"))),
                    json.readTree(Files.readAllBytes(capture.output.resolve("response.json"))));
                Files.write(capture.output.resolve("composition-verification.json"),json.writeValueAsBytes(composition),StandardOpenOption.CREATE_NEW);
                var ranking=SimulationFeedRankingVerifier.verify(json.readTree(Files.readAllBytes(capture.output.resolve("request.json"))),json.readTree(Files.readAllBytes(capture.output.resolve("response.json"))));
                Files.write(capture.output.resolve("ranking-verification.json"),json.writeValueAsBytes(ranking),StandardOpenOption.CREATE_NEW);
            }
        }
        }
    }
    @Override public synchronized <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,HttpResponse.BodyHandler<T> handler) {
        Capture capture=active;
        if(closed || capture==null || capture.invoked || !endpoint.equals(request.uri()))throw new IllegalStateException("FEED_CAPTURE_UNBOUND_REQUEST");
        capture.invoked=true;
        // Save the exact production request body, including its actual random request/context IDs.
        var bytes=new ByteArrayOutputStream();var completed=new CompletableFuture<byte[]>();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer b){byte[] chunk=new byte[b.remaining()];b.get(chunk);bytes.writeBytes(chunk);}
            public void onError(Throwable e){completed.completeExceptionally(e);}
            public void onComplete(){completed.complete(bytes.toByteArray());}
        });
        try {Files.write(capture.output.resolve("request.json"),completed.join(),StandardOpenOption.CREATE_NEW);}
        catch(IOException failure){capture.failure=failure;throw new UncheckedIOException(failure);}
        return delegate.sendAsync(request,handler).thenApply(response->{
            if(!(response.body() instanceof byte[] body))throw new IllegalStateException("FEED_EXPECTED_BYTE_RESPONSE");
            synchronized(capture) {
            if(capture.sealed)return response; // A timed-out call cannot mutate a finalized event artifact.
            try {
                Files.write(capture.output.resolve("response.json"),body,StandardOpenOption.CREATE_NEW);
                Files.write(capture.output.resolve("http.json"),json.writeValueAsBytes(Map.of("status",response.statusCode(),
                    "contentType",response.headers().firstValue("Content-Type").orElse(""),"responseBytes",body.length)),StandardOpenOption.CREATE_NEW);
                capture.responseCaptured=true;
            } catch(IOException failure){capture.failure=failure;throw new UncheckedIOException(failure);}
            return response;
            }
        });
    }
    @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new UnsupportedOperationException();}
    @Override public <T> HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new UnsupportedOperationException();}
    @Override public Optional<CookieHandler> cookieHandler(){return delegate.cookieHandler();}
    @Override public Optional<Duration> connectTimeout(){return delegate.connectTimeout();}
    @Override public Redirect followRedirects(){return delegate.followRedirects();}
    @Override public Optional<ProxySelector> proxy(){return delegate.proxy();}
    @Override public SSLContext sslContext(){return delegate.sslContext();}
    @Override public SSLParameters sslParameters(){return delegate.sslParameters();}
    @Override public Optional<Authenticator> authenticator(){return delegate.authenticator();}
    @Override public Version version(){return delegate.version();}
    @Override public Optional<Executor> executor(){return delegate.executor();}
    @Override public synchronized void close(){closed=true;delegate.close();}
}
