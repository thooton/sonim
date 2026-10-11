package com.teslacharge.xp3.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;

final class HttpJson {
    static final class Response {
        final int status;
        final String text;

        Response(int status, String text) {
            this.status = status;
            this.text = text;
        }

        JSONObject object() throws ApiException {
            try { return new JSONObject(text); }
            catch (JSONException e) { throw new ApiException("The service returned an unreadable response.", 502, e); }
        }

        JSONArray array() throws ApiException {
            try { return new JSONArray(text); }
            catch (JSONException e) { throw new ApiException("The service returned an unreadable response.", 502, e); }
        }
    }

    private HttpJson() {}

    static Response get(String url, Map<String, String> headers) throws ApiException {
        return request("GET", url, headers, null);
    }

    static Response post(String url, Map<String, String> headers, JSONObject body) throws ApiException {
        return request("POST", url, headers, body == null ? "{}" : body.toString());
    }

    private static Response request(String method, String url, Map<String, String> headers, String body) throws ApiException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(35_000);
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : (headers == null ? Collections.<String, String>emptyMap() : headers).entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            StringBuilder text = new StringBuilder();
            if (stream != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) text.append(line).append('\n');
                }
            }
            return new Response(status, text.toString());
        } catch (Exception e) {
            if (e instanceof ApiException) throw (ApiException) e;
            throw new ApiException("Network error: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), 0, e);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
