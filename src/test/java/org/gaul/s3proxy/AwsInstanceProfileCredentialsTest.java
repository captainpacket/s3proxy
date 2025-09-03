package org.gaul.s3proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jclouds.Constants;
import org.junit.Test;

public final class AwsInstanceProfileCredentialsTest {
    @Test
    public void testBackendInitializesWithInstanceCredentials() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/latest/api/token", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/latest/meta-data/iam/security-credentials/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                hits.incrementAndGet();
                byte[] body = "stub-role".getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.createContext("/latest/meta-data/iam/security-credentials/stub-role", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                hits.incrementAndGet();
                String json = "{\"AccessKeyId\":\"stub\",\"SecretAccessKey\":\"stub\",\"Token\":\"stub\",\"Expiration\":\"2030-01-01T00:00:00Z\"}";
                byte[] body = json.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        });
        server.start();
        String metadataEndpoint = "http://localhost:" + server.getAddress().getPort();
        System.setProperty("com.amazonaws.sdk.ec2MetadataServiceEndpointOverride", metadataEndpoint);

        Properties props = new Properties();
        props.setProperty(Constants.PROPERTY_PROVIDER, "aws-s3");
        props.setProperty("jclouds.aws.use-instance-credentials", "true");
        props.setProperty(Constants.PROPERTY_ENDPOINT, "http://localhost:1");
        props.setProperty("jclouds.region", "us-east-1");

        var method = Main.class.getDeclaredMethod("createBlobStore", Properties.class, java.util.concurrent.ExecutorService.class);
        method.setAccessible(true);
        var blobStore = method.invoke(null, props, Executors.newSingleThreadExecutor());

        assertThat(blobStore).isNotNull();
        assertThat(hits.get()).isGreaterThanOrEqualTo(2);

        server.stop(0);
        System.clearProperty("com.amazonaws.sdk.ec2MetadataServiceEndpointOverride");
    }
}
