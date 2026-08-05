package com.moex.trinity.marketdata;

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
 * T-Invest gRPC with channel-scoped Russian Trusted CA (same approach as pairs broker client).
 */
final class TInvestApiFactory {

    private static final Logger log = LoggerFactory.getLogger(TInvestApiFactory.class);
    private static final String APP_NAME = "trinity-marketdata";
    private static final String API_TARGET = "invest-public-api.tinkoff.ru:443";
    private static final String SANDBOX_TARGET = "sandbox-invest-public-api.tinkoff.ru:443";
    private static final String RUSSIAN_CA = "/certs/russian-trusted-ca-bundle.pem";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_INBOUND = 16 * 1024 * 1024;

    private TInvestApiFactory() {
    }

    static InvestApi create(String token, boolean sandbox) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("T-Invest token required");
        }
        try {
            Channel channel = buildChannel(token.trim(), sandbox, true);
            return sandbox ? InvestApi.createSandbox(channel) : InvestApi.create(channel);
        } catch (Exception ex) {
            log.warn("Custom SSL channel failed ({}), falling back to InvestApi.create*", ex.toString());
            return sandbox ? InvestApi.createSandbox(token.trim()) : InvestApi.create(token.trim());
        }
    }

    private static Channel buildChannel(String token, boolean sandbox, boolean trustRussianCa) {
        String target = sandbox ? SANDBOX_TARGET : API_TARGET;
        Metadata headers = new Metadata();
        InvestApi.addAuthHeader(headers, token);
        InvestApi.addAppNameHeader(headers, APP_NAME);
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(target)
                .intercept(new ClientInterceptor[]{MetadataUtils.newAttachHeadersInterceptor(headers)})
                .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .keepAliveTimeout(60, TimeUnit.SECONDS)
                .maxInboundMessageSize(MAX_INBOUND)
                .useTransportSecurity();
        if (trustRussianCa) {
            builder.sslContext(sslContext());
        }
        return builder.build();
    }

    private static SslContext sslContext() {
        try (InputStream ca = TInvestApiFactory.class.getResourceAsStream(RUSSIAN_CA)) {
            if (ca == null) {
                throw new IllegalStateException("Missing " + RUSSIAN_CA);
            }
            return GrpcSslContexts.forClient().trustManager(ca).build();
        } catch (Exception ex) {
            log.warn("Russian CA load failed ({}), trust-all for this channel", ex.getMessage());
            try {
                return GrpcSslContexts.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
            } catch (Exception e2) {
                throw new IllegalStateException("SSL context: " + e2.getMessage(), e2);
            }
        }
    }
}
