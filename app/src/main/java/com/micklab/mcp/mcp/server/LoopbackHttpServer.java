package com.micklab.mcp.mcp.server;

import android.util.Log;

import com.micklab.mcp.mcp.Jsons;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LoopbackHttpServer {
    private static final String TAG = "LoopbackHttpServer";

    private final McpJsonRpcServer jsonRpcServer;
    private final int port;
    private final int maxBodyBytes;
    private final ExecutorService requestExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private ServerSocket serverSocket;
    private Thread acceptThread;

    public LoopbackHttpServer(McpJsonRpcServer jsonRpcServer, int port, int maxBodyBytes) {
        this.jsonRpcServer = jsonRpcServer;
        this.port = port;
        this.maxBodyBytes = maxBodyBytes;
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }
        serverSocket = new ServerSocket();
        serverSocket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 32);
        running.set(true);
        acceptThread = new Thread(this::acceptLoop, "mcp-loopback-accept");
        acceptThread.start();
    }

    public synchronized void stop() {
        running.set(false);
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Ignore close failures during shutdown.
            }
            serverSocket = null;
        }
        requestExecutor.shutdownNow();
    }

    public boolean isRunning() {
        return running.get();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                requestExecutor.execute(() -> handleSocket(socket));
            } catch (SocketException exception) {
                if (running.get()) {
                    Log.e(TAG, "Loopback socket error", exception);
                }
            } catch (IOException exception) {
                if (running.get()) {
                    Log.e(TAG, "Accept loop failed", exception);
                }
            }
        }
    }

    private void handleSocket(Socket socket) {
        try {
            socket.setSoTimeout(5_000);
            HttpRequest request = readRequest(socket.getInputStream());
            HttpResponse response = route(request);
            writeResponse(socket.getOutputStream(), response);
        } catch (Exception exception) {
            Log.e(TAG, "Request handling failed", exception);
            try {
                writeResponse(socket.getOutputStream(), HttpResponse.json(
                        500,
                        Jsons.objectOf(
                                "error", "internal_server_error",
                                "message", exception.getMessage()
                        ).toString()
                ));
            } catch (Exception ignored) {
                // Socket is already broken.
            }
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Ignore close failures.
            }
        }
    }

    private HttpResponse route(HttpRequest request) throws Exception {
        if ("GET".equals(request.method) && "/health".equals(request.path)) {
            return HttpResponse.json(
                    200,
                    Jsons.objectOf(
                            "status", "ok",
                            "port", port,
                            "transport", "loopback-http"
                    ).toString()
            );
        }
        if (!"/rpc".equals(request.path) && !"/mcp".equals(request.path)) {
            return HttpResponse.json(404, Jsons.objectOf("error", "not_found").toString());
        }
        if (!"POST".equals(request.method)) {
            return HttpResponse.json(
                    405,
                    Jsons.objectOf("error", "method_not_allowed").toString()
            );
        }

        String requestBody = new String(request.body, StandardCharsets.UTF_8);
        Object parsed = new JSONTokener(requestBody).nextValue();
        if (parsed instanceof JSONObject) {
            JSONObject response = jsonRpcServer.handle((JSONObject) parsed);
            if (response == null) {
                return new HttpResponse(204, new byte[0], "application/json; charset=utf-8");
            }
            return HttpResponse.json(200, response.toString());
        }
        if (parsed instanceof JSONArray) {
            JSONArray requestArray = (JSONArray) parsed;
            JSONArray responses = new JSONArray();
            for (int index = 0; index < requestArray.length(); index++) {
                Object item = requestArray.opt(index);
                JSONObject rpcResponse = jsonRpcServer.handle(
                        item instanceof JSONObject ? (JSONObject) item : Jsons.objectOf()
                );
                if (rpcResponse != null) {
                    responses.put(rpcResponse);
                }
            }
            if (responses.length() == 0) {
                return new HttpResponse(204, new byte[0], "application/json; charset=utf-8");
            }
            return HttpResponse.json(200, responses.toString());
        }
        return HttpResponse.json(
                400,
                Jsons.objectOf(
                        "error", "invalid_json",
                        "message", "Expected a JSON-RPC object or batch array."
                ).toString()
        );
    }

    private HttpRequest readRequest(InputStream inputStream) throws IOException {
        byte[] headerBytes = readUntilHeaderTerminator(inputStream);
        String headerText = new String(headerBytes, StandardCharsets.US_ASCII);
        String[] lines = headerText.split("\r\n");
        if (lines.length == 0) {
            throw new EOFException("HTTP request line is missing.");
        }

        String[] requestLine = lines[0].split(" ");
        if (requestLine.length < 2) {
            throw new IOException("Malformed HTTP request line.");
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isEmpty()) {
                continue;
            }
            int separator = lines[index].indexOf(':');
            if (separator <= 0) {
                continue;
            }
            String name = lines[index].substring(0, separator).trim().toLowerCase();
            String value = lines[index].substring(separator + 1).trim();
            headers.put(name, value);
        }

        int contentLength = 0;
        if (headers.containsKey("content-length")) {
            contentLength = Integer.parseInt(headers.get("content-length"));
        }
        if (contentLength < 0 || contentLength > maxBodyBytes) {
            throw new IOException("HTTP request body exceeds the configured limit.");
        }

        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < contentLength) {
            int read = inputStream.read(body, offset, contentLength - offset);
            if (read == -1) {
                throw new EOFException("Unexpected end of HTTP body.");
            }
            offset += read;
        }
        return new HttpRequest(requestLine[0], requestLine[1], headers, body);
    }

    private byte[] readUntilHeaderTerminator(InputStream inputStream) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        int matched = 0;
        while (outputStream.size() < 16 * 1024) {
            int nextByte = inputStream.read();
            if (nextByte == -1) {
                throw new EOFException("Unexpected end of HTTP headers.");
            }
            outputStream.write(nextByte);
            switch (matched) {
                case 0:
                    matched = nextByte == '\r' ? 1 : 0;
                    break;
                case 1:
                    matched = nextByte == '\n' ? 2 : 0;
                    break;
                case 2:
                    matched = nextByte == '\r' ? 3 : 0;
                    break;
                case 3:
                    if (nextByte == '\n') {
                        return outputStream.toByteArray();
                    }
                    matched = 0;
                    break;
                default:
                    matched = 0;
            }
        }
        throw new IOException("HTTP headers are too large.");
    }

    private void writeResponse(OutputStream outputStream, HttpResponse response) throws IOException {
        byte[] body = response.body;
        StringBuilder headers = new StringBuilder();
        headers.append("HTTP/1.1 ")
                .append(response.statusCode)
                .append(' ')
                .append(reasonPhrase(response.statusCode))
                .append("\r\n");
        headers.append("Content-Type: ").append(response.contentType).append("\r\n");
        headers.append("Content-Length: ").append(body.length).append("\r\n");
        headers.append("Connection: close\r\n");
        headers.append("\r\n");
        outputStream.write(headers.toString().getBytes(StandardCharsets.US_ASCII));
        outputStream.write(body);
        outputStream.flush();
    }

    private String reasonPhrase(int statusCode) {
        switch (statusCode) {
            case 200:
                return "OK";
            case 204:
                return "No Content";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 500:
                return "Internal Server Error";
            default:
                return "HTTP";
        }
    }

    private static final class HttpRequest {
        private final String method;
        private final String path;
        private final Map<String, String> headers;
        private final byte[] body;

        private HttpRequest(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.headers = headers;
            this.body = body;
        }
    }

    private static final class HttpResponse {
        private final int statusCode;
        private final byte[] body;
        private final String contentType;

        private HttpResponse(int statusCode, byte[] body, String contentType) {
            this.statusCode = statusCode;
            this.body = body;
            this.contentType = contentType;
        }

        private static HttpResponse json(int statusCode, String body) {
            return new HttpResponse(
                    statusCode,
                    body.getBytes(StandardCharsets.UTF_8),
                    "application/json; charset=utf-8"
            );
        }
    }
}
