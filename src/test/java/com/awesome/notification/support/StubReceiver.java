package com.awesome.notification.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * A webhook receiver on 127.0.0.1 (a JDK HTTP server instead of the backend's WireMock): answers
 * scripted responses per path and records every request with its headers and raw body.
 */
public final class StubReceiver implements AutoCloseable {

    private final HttpServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Map<String, Answer> answers = new ConcurrentHashMap<>();
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    /** A recorded request; header names are lower case. */
    public record Request(String method, String path, Map<String, String> headers, String body) {

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    private record Answer(int status, String body, Map<String, String> headers) {
    }

    public StubReceiver() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void answer(String path, int status, String body) {
        answer(path, status, body, Map.of());
    }

    public void answer(String path, int status, String body, Map<String, String> headers) {
        answers.put(path, new Answer(status, body, headers));
    }

    public void reset() {
        answers.clear();
        requests.clear();
    }

    public List<Request> requests(String path) {
        return requests.stream().filter(request -> request.path().equals(path)).toList();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    private void handle(HttpExchange exchange) throws IOException {
        Map<String, String> headers = new TreeMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        String path = exchange.getRequestURI().getRawPath();
        requests.add(new Request(exchange.getRequestMethod(), path, headers, body));
        Answer answer = answers.getOrDefault(path, new Answer(404, "", Map.of()));
        answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
