package com.foxsrv.coin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class HttpApiClient {
    private final HttpClient client;
    private final String baseUrl;

    public HttpApiClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") 
            ? baseUrl.substring(0, baseUrl.length()-1) 
            : baseUrl;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    private HttpRequest.Builder builder(String endpoint, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder()
            .uri(URI.create(baseUrl + endpoint))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json");
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return b;
    }

    public String post(String endpoint, String json, String token) throws Exception {
        HttpRequest req = builder(endpoint, token)
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    public String get(String endpoint, String token) throws Exception {
        HttpRequest req = builder(endpoint, token)
            .GET()
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }
}
