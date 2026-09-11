package com.automanuales.app;

import android.util.Log;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

@CapacitorPlugin(name = "StartMyCar")
public class StartMyCarPlugin extends Plugin {

    @PluginMethod
    public void getPage(PluginCall call) {
        String urlString = call.getString("url");

        if (urlString == null || urlString.trim().isEmpty()) {
            call.reject("No se proporcionó una URL.");
            return;
        }

        new Thread(() -> {
            HttpURLConnection connection = null;

            try {
                URL url = new URL(urlString);

                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(30000);
                connection.setInstanceFollowRedirects(true);

                connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Android) AutoManuales"
                );

                int responseCode = connection.getResponseCode();

                InputStream stream;

                if (responseCode >= 200 && responseCode < 400) {
                    stream = connection.getInputStream();
                } else {
                    stream = connection.getErrorStream();
                }

                String body = readStream(stream);

                if (responseCode < 200 || responseCode >= 300) {
                    call.reject(
                            "StartMyCar respondió HTTP " + responseCode
                    );
                    return;
                }

                JSObject result = new JSObject();
                result.put("status", responseCode);
                result.put("body", body);
                result.put("url", connection.getURL().toString());

                call.resolve(result);

            } catch (Exception e) {
                Log.e("StartMyCar", "Error al consultar StartMyCar", e);
                call.reject(
                        "No se pudo consultar StartMyCar: "
                                + e.getMessage()
                );
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private String readStream(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        try (
                BufferedReader reader =
                        new BufferedReader(
                                new InputStreamReader(stream)
                        )
        ) {
            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }

        return builder.toString();
    }
}