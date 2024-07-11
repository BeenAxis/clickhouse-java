package com.clickhouse.client.api.internal;

import com.clickhouse.client.ClickHouseNode;
import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.ClientException;
import com.clickhouse.client.api.ServerException;
import com.clickhouse.client.http.config.ClickHouseHttpOption;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.DefaultThreadFactory;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.net.URIBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class HttpAPIClientHelper {
    private static final Logger LOG = LoggerFactory.getLogger(Client.class);

    private CloseableHttpClient httpClient;

    private Map<String, String> chConfiguration;

    private ExecutorService executorService;

    private RequestConfig baseRequestConfig;

    public HttpAPIClientHelper(Map<String, String> configuration) {
        this.chConfiguration = configuration;
        this.httpClient = createHttpClient(configuration, null);
        this.baseRequestConfig = RequestConfig.custom()
                .setConnectionRequestTimeout(1000, TimeUnit.MILLISECONDS)
                .build();
        this.executorService = Executors.newCachedThreadPool(new DefaultThreadFactory("clickhouse-client"));
    }

    public CloseableHttpClient createHttpClient(Map<String, String> chConfig, Map<String, Serializable> requestConfig) {
        final CloseableHttpClient httpclient = HttpClientBuilder.create()

                .build();


        return httpclient;
    }

    /**
     * Reads status line and if error tries to parse response body to get server error message.
     *
     * @param httpResponse - HTTP response
     * @return
     */
    public Exception readError(ClassicHttpResponse httpResponse) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(8192)) {
            httpResponse.getEntity().writeTo(out);
            String message = new String(out.toByteArray(), StandardCharsets.UTF_8);
            int serverCode = httpResponse.getFirstHeader("X-ClickHouse-Exception-Code") != null
                    ? Integer.parseInt(httpResponse.getFirstHeader("X-ClickHouse-Exception-Code").getValue())
                    : 0;
            return new ServerException(serverCode, message);
        } catch (IOException e) {
            throw new ClientException("Failed to read response body", e);
        }
    }

    public CompletableFuture<ClassicHttpResponse> executeRequest(ClickHouseNode server,
                                                                 String sql, Map<String, Object> requestConfig) {
        return executeRequest(server, new StringEntity(sql), requestConfig);
    }

    public CompletableFuture<ClassicHttpResponse> executeRequest(ClickHouseNode server,
                                                                 HttpEntity httpEntity, final Map<String, Object> requestConfig) {

        CompletableFuture<ClassicHttpResponse> responseFuture = CompletableFuture.supplyAsync(() -> {


            HttpHost target = new HttpHost(server.getHost(), server.getPort());

            URI uri;
            try {
                URIBuilder uriBuilder = new URIBuilder(server.getBaseUri());
                addQueryParams(uriBuilder, chConfiguration, requestConfig);
                uri = uriBuilder.build();
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
            HttpPost req = new HttpPost(uri);
            addHeaders(req, chConfiguration, requestConfig);

            RequestConfig httpReqConfig = RequestConfig.copy(baseRequestConfig)
                    .build();
            req.setConfig(httpReqConfig);
            req.setEntity(httpEntity);

            HttpClientContext context = HttpClientContext.create();

            try {
                ClassicHttpResponse httpResponse = httpClient.executeOpen(target, req, context);
                if (httpResponse.getCode() >= 400 && httpResponse.getCode() < 500) {
                    try {
                        throw readError(httpResponse);
                    } finally {
                        httpResponse.close();
                    }
                } else if (httpResponse.getCode() >= 500) {
                    httpResponse.close();
                    return httpResponse;
                }
                return httpResponse;

            } catch (UnknownHostException e) {
                LOG.warn("Host '{}' unknown", target);
            } catch (ConnectException | NoRouteToHostException e) {
                LOG.warn("Failed to connect to '{}': {}", target, e.getMessage());
            } catch (ServerException e) {
                throw e;
            } catch (Exception e) {
                throw new ClientException("Failed to execute request", e);
            }

            return null;
        }, executorService);

        return responseFuture;
    }

    private void addHeaders(HttpPost req, Map<String, String> chConfig, Map<String, Object> requestConfig) {
        req.addHeader("Content-Type", "text/plain");
        req.addHeader("Accept", "text/plain");

        if (requestConfig != null) {
            if (requestConfig.containsKey("format")) {
                req.addHeader("x-clickhouse-format", requestConfig.get("format"));
            }
        }
    }

    private void addQueryParams(URIBuilder req, Map<String, String> chConfig, Map<String, Object> requestConfig) {
        if (requestConfig != null) {
            if (requestConfig.containsKey(ClickHouseHttpOption.WAIT_END_OF_QUERY.getKey())) {
                req.addParameter(ClickHouseHttpOption.WAIT_END_OF_QUERY.getKey(),
                        requestConfig.get(ClickHouseHttpOption.WAIT_END_OF_QUERY.getKey()).toString());
            }
            if (requestConfig.containsKey("query_id")) {
                req.addParameter("query_id", requestConfig.get("query_id").toString());
            }
        }
    }
}
