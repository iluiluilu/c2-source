package com.aismartstore.backend.search;

import java.security.NoSuchAlgorithmException;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLContext;
import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.impl.auth.BasicCredentialsProvider;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.transport.OpenSearchTransport;
import org.opensearch.client.transport.httpclient5.ApacheHttpClient5TransportBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * search-fulltext-01-tim-kiem-co-ban.md Task 1/3: a single {@link
 * OpenSearchClient} bean, backed by the Apache HttpClient5 transport (pom.xml
 * Task 1 dependency), pointed at the cluster in {@code docker-compose.yml}
 * (service {@code opensearch}). The tighter, user-facing, runtime-tunable
 * {@code system-config.search.query_timeout_ms} is still enforced per-call by
 * the caller ({@code SearchService}, §4 step 4) - the fixed connect/response
 * timeouts set here are only the backstop under that: a client5 request with
 * no explicit RequestConfig does not fail fast on its own when the target is
 * unreachable (observed hanging indefinitely, both from {@link
 * SearchIndexBootstrap} at app startup and from a test calling the client
 * directly), so every call through this bean gets a bound by default even if
 * a future caller forgets to add its own.
 */
@Configuration
public class OpenSearchClientConfig {

    private static final int CONNECT_TIMEOUT_SECONDS = 3;
    private static final int RESPONSE_TIMEOUT_SECONDS = 30;

    @Bean
    public OpenSearchTransport openSearchTransport(OpenSearchProperties properties)
            throws NoSuchAlgorithmException, KeyManagementException, KeyStoreException {
        HttpHost host = new HttpHost(properties.scheme(), properties.host(), properties.port());
        boolean authEnabled = !properties.username().isBlank() && !properties.password().isBlank();

        ApacheHttpClient5TransportBuilder builder = ApacheHttpClient5TransportBuilder.builder(host)
                .setRequestConfigCallback(rc -> rc
                        .setConnectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .setResponseTimeout(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS));

        if (authEnabled) {
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(
                    new AuthScope(host),
                    new UsernamePasswordCredentials(properties.username(), properties.password().toCharArray()));

            // The cluster's security plugin ships a self-signed cert with no
            // public CA (see [[README.md]] on why: internal-only traffic, no
            // domain involved) - trust-all + no hostname check is the
            // deliberate equivalent of the Python clients' verify_certs=False
            // already documented for this same cluster, not an oversight.
            SSLContext sslContext = SSLContextBuilder.create()
                    .loadTrustMaterial(null, TrustAllStrategy.INSTANCE)
                    .build();

            builder.setHttpClientConfigCallback(httpClientBuilder -> httpClientBuilder
                    .setDefaultCredentialsProvider(credentialsProvider)
                    .setConnectionManager(PoolingAsyncClientConnectionManagerBuilder.create()
                            .setTlsStrategy(ClientTlsStrategyBuilder.create()
                                    .setSslContext(sslContext)
                                    .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
                                    .build())
                            .build()));
        }

        return builder.build();
    }

    @Bean
    public OpenSearchClient openSearchClient(OpenSearchTransport transport) {
        return new OpenSearchClient(transport);
    }
}
