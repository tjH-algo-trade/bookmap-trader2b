package ai.tradingassist.bookmap;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

/** Stateless transport. Intentionally never retries a mutation or follows redirects. */
public final class BridgeClient {
    static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private final HttpClient http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final URI base;
    private final String token;
    private final Duration timeout;
    public BridgeClient(String url, String token) { this(url, token, Duration.ofSeconds(8)); }
    BridgeClient(String url, String token, Duration timeout) {
        base = URI.create(url.endsWith("/") ? url.substring(0, url.length()-1) : url);
        boolean loopback = "127.0.0.1".equals(base.getHost()) || "localhost".equals(base.getHost());
        if (!("https".equals(base.getScheme()) || ("http".equals(base.getScheme()) && loopback))
                || base.getHost() == null || base.getRawUserInfo() != null
                || base.getRawQuery() != null || base.getRawFragment() != null)
            throw new IllegalArgumentException("Use HTTPS, or HTTP on localhost/127.0.0.1 only; no URL credentials/query/fragment");
        if (token.contains("\n") || token.contains("\r")) throw new IllegalArgumentException("Invalid token");
        this.token = token; this.timeout = timeout;
    }
    public String request(String method, String path, String body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base.toString() + path))
            .timeout(timeout).header("Accept", "application/json");
        if (!token.isEmpty()) b.header("Authorization", "Bearer " + token);
        if (key != null) b.header("Idempotency-Key", key);
        if (body == null) b.GET();
        else b.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        // The future completes only after the entire body has arrived. Request.timeout
        // alone does not bound a server that sends headers and then stalls its body.
        CompletableFuture<HttpResponse<String>> pending = http.sendAsync(b.build(), info -> new LimitedBody());
        final HttpResponse<String> r;
        try {
            r = pending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException ex) {
            pending.cancel(true);
            throw new HttpTimeoutException("Response deadline exceeded");
        } catch (InterruptedException ex) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw ex;
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            throw new IOException("Response failed", ex.getCause());
        }
        // Raw backend response is deliberate: never infer a fill from HTTP 200.
        return "HTTP " + r.statusCode() + "\n" + r.body();
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<String> {
        private final HttpResponse.BodySubscriber<String> delegate =
            HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
        private Flow.Subscription subscription;
        private int received;
        private boolean failed;
        @Override public CompletionStage<String> getBody() { return delegate.getBody(); }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            delegate.onSubscribe(subscription);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (failed) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_RESPONSE_BYTES - received) {
                    failed = true;
                    subscription.cancel();
                    delegate.onError(new IOException("Response exceeds " + MAX_RESPONSE_BYTES + " bytes"));
                    return;
                }
                received += buffer.remaining();
            }
            delegate.onNext(buffers);
        }
        @Override public void onError(Throwable error) { if (!failed) delegate.onError(error); }
        @Override public void onComplete() { if (!failed) delegate.onComplete(); }
    }
    public static String identifier(String s) {
        if (s == null || !s.matches("[A-Za-z0-9._-]{1,80}"))
            throw new IllegalArgumentException("Use 1–80 letters, digits, dots, hyphens or underscores");
        return s;
    }
    public static String order(String id, String symbol, String account, String side, int qty) {
        identifier(id); identifier(symbol); identifier(account);
        if (!(side.equals("buy") || side.equals("sell")) || qty < 1 || qty > 10000)
            throw new IllegalArgumentException("Invalid side or quantity (1–10000)");
        return "{\"client_order_id\":\""+id+"\",\"symbol\":\""+symbol+"\",\"account_id\":\""+account
            +"\",\"side\":\""+side+"\",\"quantity\":"+qty+",\"order_type\":\"market\",\"time_in_force\":\"day\",\"environment\":\"paper\"}";
    }
}
