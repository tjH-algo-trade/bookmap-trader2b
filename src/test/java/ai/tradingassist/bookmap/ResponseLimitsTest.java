package ai.tradingassist.bookmap;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;

public class ResponseLimitsTest {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        server.setExecutor(handlers);
        CountDownLatch headers = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch interruptHeaders = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        server.createContext("/stall", exchange -> {
            writes.incrementAndGet();
            try {
                exchange.sendResponseHeaders(202, 2);
                exchange.getResponseBody().write('O');
                exchange.getResponseBody().flush();
                if (exchange.getRequestURI().getPath().endsWith("interrupt")) interruptHeaders.countDown();
                else headers.countDown();
                release.await(5, TimeUnit.SECONDS);
                exchange.getResponseBody().write('K');
            } catch (IOException ignored) {
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/body", exchange -> {
            boolean oversized = exchange.getRequestURI().getPath().endsWith("large");
            byte[] bytes = new byte[BridgeClient.MAX_RESPONSE_BYTES + (oversized ? 1 : 0)];
            java.util.Arrays.fill(bytes, (byte)'x');
            try {
                // Chunked transfer ensures the client cannot rely on Content-Length.
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(bytes);
            } catch (IOException ignored) {} finally { exchange.close(); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            BridgeClient client = new BridgeClient(base, "", Duration.ofMillis(750));
            long start = System.nanoTime();
            try {
                client.request("POST", "/stall", "{}", "deadline-test");
                throw new AssertionError("stalled body must time out");
            } catch (HttpTimeoutException expected) {}
            check(headers.getCount() == 0, "server sent headers before timeout");
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(3), "whole response deadline");
            check(writes.get() == 1, "mutation submitted once");
            // The same caller can make progress after a timed-out response.
            check(client.request("GET", "/body/exact", null, null).length()
                == "HTTP 200\n".length() + BridgeClient.MAX_RESPONSE_BYTES, "exact limit accepted");
            try {
                client.request("GET", "/body/large", null, null);
                throw new AssertionError("oversized chunked body accepted");
            } catch (IOException expected) {
                check(expected.getMessage().contains("Response exceeds"), "size error retained");
            }
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread caller = new Thread(() -> {
                try {
                    new BridgeClient(base, "", Duration.ofSeconds(10))
                        .request("POST", "/stall/interrupt", "{}", "interrupt-test");
                    failure.set(new AssertionError("interrupted request completed"));
                } catch (InterruptedException expected) {
                    interrupted.set(Thread.currentThread().isInterrupted());
                } catch (Throwable ex) { failure.set(ex); }
            });
            caller.start();
            try {
                check(interruptHeaders.await(3, TimeUnit.SECONDS), "interrupt test received headers");
            } finally { caller.interrupt(); caller.join(3000); }
            check(!caller.isAlive() && interrupted.get() && failure.get() == null, "interruption releases worker");
            SwingUtilities.invokeAndWait(() -> {
                try {
                    BoundedLogDocument log = new BoundedLogDocument();
                    log.insertString(0, "a".repeat(BoundedLogDocument.MAX_CHARACTERS), null);
                    log.insertString(log.getLength(), "newest", null);
                    check(log.getLength() == BoundedLogDocument.MAX_CHARACTERS, "cumulative log capped");
                    check(log.getText(log.getLength() - 6, 6).equals("newest"), "newest retained");
                    log.insertString(log.getLength(), "b".repeat(BoundedLogDocument.MAX_CHARACTERS + 100) + "tail", null);
                    check(log.getLength() == BoundedLogDocument.MAX_CHARACTERS, "single huge append capped");
                    check(log.getText(log.getLength() - 4, 4).equals("tail"), "huge append tail retained");
                } catch (javax.swing.text.BadLocationException ex) { throw new AssertionError(ex); }
            });
            System.out.println("PASS: stalled body deadline, no retry, recovery, response byte limit, interruption, bounded UI log.");
        } finally {
            release.countDown();
            server.stop(0);
            handlers.shutdownNow();
        }
    }
}
