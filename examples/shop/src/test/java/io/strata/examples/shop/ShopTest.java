package io.strata.examples.shop;

import com.typesafe.config.ConfigFactory;
import io.strata.Services;
import io.strata.Strata;
import io.strata.StrataRuntime;
import io.strata.http.pekko.PekkoHttpExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The shop end to end: discovered services, other entities, timers, idempotent HTTP. */
class ShopTest {
    @TempDir Path dir;
    final List<String> mails = new CopyOnWriteArrayList<>();
    final HttpClient http = HttpClient.newHttpClient();

    /** The app as it runs (discovered @Provides services), with a short payment window and a mailer we can read. */
    StrataRuntime start() {
        ShopPolicy configured = new ConfiguredPolicy();
        ShopPolicy quick = new ShopPolicy() {
            public long price(String sku) { return configured.price(sku); }
            public Duration paymentWindow() { return Duration.ofMillis(500); }
        };
        return Strata.start(ConfigFactory.parseMap(Map.of(
                        "strata.http.port", 0,
                        "strata.pekko.system.pekko.remote.artery.canonical.port", 0,
                        "strata.store.jdbc.url", "jdbc:h2:file:" + dir.resolve("db"),
                        "strata.timers.poll-interval", "100ms")).withFallback(ConfigFactory.load()).resolve(),
                Services.builder().add(ShopPolicy.class, quick).add(Mailer.class, mails::add).build()); // these win over @Provides ones
    }

    static <T> T await(CompletionStage<T> s) throws Exception { return s.toCompletableFuture().get(30, TimeUnit.SECONDS); }

    @Test
    void placePayAndExpire() throws Exception {
        try (var shop = start()) {
            await(StockClient.of(shop, "tea").restock(10));
            await(StockClient.of(shop, "mug").restock(2));

            // Placed and paid: stock reserved, card charged by the discovered DemoPayments, mail sent.
            var o1 = OrderClient.of(shop, "o1");
            assertEquals(Order.Status.AWAITING_PAYMENT, await(o1.place(List.of(new Order.Line("tea", 3), new Order.Line("mug", 1)))));
            assertEquals(7, await(StockClient.of(shop, "tea").available()));
            assertTrue(await(o1.pay("4242-4242")).startsWith("R-"));
            assertEquals(Order.Status.PAID, await(o1.status()));
            assertEquals(1, mails.size());
            assertTrue(mails.getFirst().contains("2550 cents"), mails.getFirst());       // 3*450 + 1200

            // A declined card: nothing changes, the order still waits.
            var o2 = OrderClient.of(shop, "o2");
            await(o2.place(List.of(new Order.Line("tea", 2))));
            var declined = assertThrows(ExecutionException.class, () -> await(o2.pay("4000-0000")));
            assertTrue(declined.getCause().getMessage().contains("card declined"));
            assertEquals(Order.Status.AWAITING_PAYMENT, await(o2.status()));
            assertEquals(5, await(StockClient.of(shop, "tea").available()));

            // Nobody pays: the timer expires it and the stock comes back.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (await(o2.status()) != Order.Status.EXPIRED) {
                assertTrue(System.nanoTime() < deadline, "o2 should expire");
                Thread.sleep(100);
            }
            assertEquals(7, await(StockClient.of(shop, "tea").available()));

            // Out of stock: what was reserved is released again, and the order stays new.
            var o3 = OrderClient.of(shop, "o3");
            var short_ = assertThrows(ExecutionException.class, () -> await(o3.place(List.of(new Order.Line("tea", 1), new Order.Line("mug", 5)))));
            assertTrue(short_.getCause().getMessage().contains("only 1 left"), short_.getCause().getMessage());
            assertEquals(7, await(StockClient.of(shop, "tea").available()), "tea reserved for o3 was released");
            assertEquals(Order.Status.NEW, await(o3.status()));
        }
    }

    @Test
    void overHttpWithAnIdempotencyKey() throws Exception {
        try (var shop = start()) {
            int port = PekkoHttpExtension.boundAddress(shop).getPort();
            post(port, "/stock/honey/restock", "5", null);
            assertEquals("\"AWAITING_PAYMENT\"", post(port, "/orders/h1/place", "[{\"sku\":\"honey\",\"quantity\":2}]", null));
            String receipt = post(port, "/orders/h1/pay", "\"4242-4242\"", "pay-h1");
            assertEquals(receipt, post(port, "/orders/h1/pay", "\"4242-4242\"", "pay-h1"), "a retried payment returns the same receipt");
            assertEquals(1, mails.size(), "and charges and mails once");
            assertTrue(get(port, "/orders/h1/summary").contains("1600 cents"));
        }
    }

    String post(int port, String path, String body, String key) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) b.header("Idempotency-Key", key);
        var res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
        return res.body();
    }

    String get(int port, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build(), HttpResponse.BodyHandlers.ofString()).body();
    }
}
