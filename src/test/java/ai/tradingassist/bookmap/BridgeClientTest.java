package ai.tradingassist.bookmap;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class BridgeClientTest {
    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger writes = new AtomicInteger();
        server.createContext("/v1/orders", e -> {
            try {
                check("Bearer test-token".equals(e.getRequestHeaders().getFirst("Authorization")), "auth");
                String path = e.getRequestURI().getPath();
                String body = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String response;
                if (e.getRequestMethod().equals("GET")) {
                    check(path.endsWith("/by-client-id/test-1"), "lookup path");
                    response = "{\"status\":\"filled\"}";
                } else {
                    writes.incrementAndGet();
                    check(e.getRequestHeaders().getFirst("Idempotency-Key") != null, "idempotency");
                    check(body.contains("\"environment\":\"paper\""), "paper flag");
                    if (!path.endsWith("/cancel")) {
                        check(body.contains("\"quantity\":5"), "quantity");
                        check(body.contains("\"symbol\":\"QQQ\""), "symbol");
                        check(body.contains("\"account_id\":\"paper-1\""), "account");
                    }
                    response = "{\"status\":\"accepted\"}";
                }
                byte[] b = response.getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(200,b.length); e.getResponseBody().write(b);
            } finally { e.close(); }
        });
        server.createContext("/reject", e -> { e.sendResponseHeaders(422,-1); e.close(); });
        server.createContext("/redirect", e -> { e.getResponseHeaders().set("Location","/v1/orders"); e.sendResponseHeaders(302,-1); e.close(); });
        server.createContext("/slow", e -> {
            writes.incrementAndGet();
            try { Thread.sleep(250); e.sendResponseHeaders(202,-1); } catch(Exception ignored) {} finally { e.close(); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            BridgeClient c = new BridgeClient(base,"test-token");
            String buy = BridgeClient.order("test-1","QQQ","paper-1","buy",5);
            check(c.request("POST","/v1/orders",buy,"test-1").contains("accepted"), "submit");
            check(c.request("POST","/v1/orders",BridgeClient.order("test-2","QQQ","paper-1","sell",5),"test-2").contains("accepted"), "sell");
            check(c.request("GET","/v1/orders/by-client-id/test-1",null,null).contains("filled"), "lookup");
            check(c.request("POST","/v1/orders/by-client-id/test-1/cancel","{\"environment\":\"paper\"}","cancel-1").contains("accepted"), "cancel");
            check(c.request("POST","/reject",buy,"reject").startsWith("HTTP 422"), "rejection retained");
            check(c.request("GET","/redirect",null,null).startsWith("HTTP 302"), "no redirect");
            int before = writes.get();
            try { new BridgeClient(base,"test-token",Duration.ofMillis(80)).request("POST","/slow",buy,"slow-1"); throw new AssertionError("expected timeout"); }
            catch (HttpTimeoutException expected) {}
            Thread.sleep(300); check(writes.get() == before+1,"no retry after timeout");
            for (String bad : new String[]{"http://example.com","https://user:pass@example.com","https://example.com?token=x"}) {
                try { new BridgeClient(bad,""); throw new AssertionError("unsafe URL accepted"); }
                catch(IllegalArgumentException expected) {}
            }
            try { BridgeClient.order("id","QQQ\"","paper-1","buy",5); throw new AssertionError("bad symbol"); }
            catch(IllegalArgumentException expected) {}
            try { BridgeClient.order("id","QQQ","paper-1","buy",0); throw new AssertionError("zero quantity"); }
            catch(IllegalArgumentException expected) {}
            System.out.println("PASS: buy, sell, auth, account, paper flag, idempotency header, lookup, cancel, rejection, no redirects, timeout without retry, URL and input validation.");
        } finally { server.stop(0); }
    }
}
