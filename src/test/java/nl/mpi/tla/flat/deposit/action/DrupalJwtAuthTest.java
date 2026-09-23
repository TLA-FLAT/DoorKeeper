package nl.mpi.tla.flat.deposit.action;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import nl.mpi.tla.flat.deposit.DepositException;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import org.junit.Test;

public class DrupalJwtAuthTest {

    private static DrupalSync sync(String server) {
        DrupalSync sync = new DrupalSync();
        sync.server = server;
        sync.authUser = "jwt-test-user";
        sync.authorization = "Basic " + Base64.getEncoder().encodeToString("jwt-test-user:secret".getBytes(StandardCharsets.UTF_8));
        sync.authMode = "jwt";
        sync.http = HttpClient.newHttpClient();
        return sync;
    }

    private static String jwt(long expiry, int serial) {
        String payload = "{\"exp\":" + expiry + ",\"serial\":" + serial + "}";
        return "header." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    @Test
    public void reusesProcessTokenAndRefreshesAfterUnauthorized() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        AtomicInteger apiRequests = new AtomicInteger();
        AtomicReference<String> tokenAuthorization = new AtomicReference<>();
        AtomicReference<String> firstBearer = new AtomicReference<>();
        AtomicReference<String> retriedBearer = new AtomicReference<>();
        long expiry = Instant.now().getEpochSecond() + 7200;
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwt/token", exchange -> {
            tokenAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, 200, "{\"token\":\"" + jwt(expiry, issued.incrementAndGet()) + "\"}");
        });
        server.createContext("/jsonapi/node/islandora_object", exchange -> {
            int request = apiRequests.incrementAndGet();
            if (request == 1)
                firstBearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (request == 3)
                retriedBearer.set(exchange.getRequestHeaders().getFirst("Authorization"));
            reply(exchange, request == 2 ? 401 : 200, "{}");
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            sync(base).apiCall("GET", base + "/jsonapi/node/islandora_object", null);
            sync(base).apiCall("GET", base + "/jsonapi/node/islandora_object", null);
            assertEquals(2, issued.get());
            assertEquals(3, apiRequests.get());
            assertEquals("Basic " + Base64.getEncoder().encodeToString("jwt-test-user:secret".getBytes(StandardCharsets.UTF_8)), tokenAuthorization.get());
            assertEquals("Bearer " + jwt(expiry, 1), firstBearer.get());
            assertEquals("Bearer " + jwt(expiry, 2), retriedBearer.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void rejectsUnusableExpiryRatherThanCachingIt() throws Exception {
        AtomicInteger issued = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwt/token", exchange -> {
            issued.incrementAndGet();
            reply(exchange, 200, "{\"token\":\"" + jwt(Instant.now().getEpochSecond() + 30, 1) + "\"}");
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            DrupalSync sync = sync(base);
            assertThrows(DepositException.class, () -> sync.apiCall("GET", base + "/jsonapi/node/islandora_object", null));
            assertThrows(DepositException.class, () -> sync.apiCall("GET", base + "/jsonapi/node/islandora_object", null));
            assertEquals(2, issued.get());
        } finally {
            server.stop(0);
        }
    }
}
