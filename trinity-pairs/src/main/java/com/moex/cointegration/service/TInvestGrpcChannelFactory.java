package com.moex.cointegration.service;

import io.grpc.Channel;
import io.grpc.ClientInterceptor;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.channel.ChannelOption;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.grpc.stub.MetadataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.tinkoff.piapi.core.InvestApi;

import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Builds T-Invest gRPC channels with SSL scoped to this client only
 * (does not touch JVM {@code cacerts}).
 */
final class TInvestGrpcChannelFactory {

    private static final Logger log = LoggerFactory.getLogger(TInvestGrpcChannelFactory.class);

    private static final String APP_NAME = "imoex";
    private static final String API_TARGET = "invest-public-api.tinkoff.ru:443";
    private static final String SANDBOX_TARGET = "sandbox-invest-public-api.tinkoff.ru:443";
    private static final String RUSSIAN_CA_RESOURCE = "/certs/russian-trusted-ca-bundle.pem";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_INBOUND_MESSAGE_SIZE = 16 * 1024 * 1024;

    private TInvestGrpcChannelFactory() {
    }

    static Channel create(String token, boolean sandbox, boolean trustAllSsl) {
        String target = sandbox ? SANDBOX_TARGET : API_TARGET;
        Metadata headers = new Metadata();
        InvestApi.addAuthHeader(headers, token);
        InvestApi.addAppNameHeader(headers, APP_NAME);

        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(target)
                .intercept(new ClientInterceptor[]{
                        MetadataUtils.newAttachHeadersInterceptor(headers)
                })
                .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .keepAliveTimeout(60, TimeUnit.SECONDS)
                .maxInboundMessageSize(MAX_INBOUND_MESSAGE_SIZE)
                .useTransportSecurity();

        if (trustAllSsl) {
            builder.sslContext(buildSslContext());
        }

        return builder.build();
    }

    /**
     * Prefer trusting the embedded Russian Trusted Root/Sub CA chain for this channel only.
     * Falls back to an insecure TrustManager if the bundle cannot be loaded.
     */
    private static SslContext buildSslContext() {
        try (InputStream ca = TInvestGrpcChannelFactory.class.getResourceAsStream(RUSSIAN_CA_RESOURCE)) {
            if (ca == null) {
                throw new IllegalStateException("Missing classpath resource " + RUSSIAN_CA_RESOURCE);
            }
            log.info("T-Invest gRPC: using embedded Russian Trusted CA bundle (channel-scoped, not JVM cacerts)");
            return GrpcSslContexts.forClient().trustManager(ca).build();
        } catch (Exception ex) {
            log.warn("T-Invest gRPC: Russian CA bundle failed ({}), falling back to trust-all for this channel only",
                    ex.getMessage());
            try {
                return GrpcSslContexts.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();
            } catch (Exception insecureEx) {
                throw new IllegalStateException(
                        "Could not build T-Invest SSL context: " + insecureEx.getMessage(), insecureEx);
            }
        }
    }
}
