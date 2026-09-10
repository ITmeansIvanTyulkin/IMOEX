package com.moex.trinity.marketdata;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
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
public final class TInvestApiFactory {

    private static final Logger log = LoggerFactory.getLogger(TInvestApiFactory.class);
    private static final String APP_NAME = "trinity-marketdata";
    private static final String API_TARGET = "invest-public-api.tinkoff.ru:443";
    private static final String SANDBOX_TARGET = "sandbox-invest-public-api.tinkoff.ru:443";
    private static final String RUSSIAN_CA = "/certs/russian-trusted-ca-bundle.pem";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(8);
    /** Unary GetOrderBook/GetCandles — fail fast when invest-public-api stalls (desk must not hang). */
    private static final Duration UNARY_DEADLINE = Duration.ofMillis(2500);
    private static final int MAX_INBOUND = 16 * 1024 * 1024;

    private TInvestApiFactory() {
    }

    /** Long-lived MarketDataStream channel — no unary deadline (would cancel the stream). */
    public static InvestApi create(String token, boolean sandbox) {
        return create(token, sandbox, false);
    }

    /** Short-lived unary client (orderbook / candles / last prices). */
    public static InvestApi createUnary(String token, boolean sandbox) {
        return create(token, sandbox, true);
    }

    private static InvestApi create(String token, boolean sandbox, boolean unaryDeadline) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("T-Invest token required");
        }
        try {
            Channel channel = buildChannel(token.trim(), sandbox, true, unaryDeadline);
            return sandbox ? InvestApi.createSandbox(channel) : InvestApi.create(channel);
        } catch (Exception ex) {
            log.warn("Custom SSL channel failed ({}), falling back to InvestApi.create*", ex.toString());
            return sandbox ? InvestApi.createSandbox(token.trim()) : InvestApi.create(token.trim());
        }
    }

    /** Always call this instead of dropping InvestApi for GC — otherwise Netty logs orphan channels. */
    public static void shutdown(InvestApi api) {
        if (api == null) {
            return;
        }
        try {
            api.destroy(2);
        } catch (Exception ex) {
            log.debug("InvestApi.destroy: {}", ex.toString());
        }
        try {
            Channel ch = api.getChannel();
            if (ch instanceof ManagedChannel mc && !mc.isTerminated()) {
                mc.shutdownNow();
                mc.awaitTermination(2, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.debug("ManagedChannel shutdown: {}", ex.toString());
        }
    }

    private static Channel buildChannel(
            String token,
            boolean sandbox,
            boolean trustRussianCa,
            boolean unaryDeadline
    ) {
        String target = sandbox ? SANDBOX_TARGET : API_TARGET;
        Metadata headers = new Metadata();
        InvestApi.addAuthHeader(headers, token);
        InvestApi.addAppNameHeader(headers, APP_NAME);
        ClientInterceptor auth = MetadataUtils.newAttachHeadersInterceptor(headers);
        ClientInterceptor[] interceptors = unaryDeadline
                ? new ClientInterceptor[]{auth, new UnaryDeadlineInterceptor(UNARY_DEADLINE)}
                : new ClientInterceptor[]{auth};
        NettyChannelBuilder builder = NettyChannelBuilder.forTarget(target)
                .intercept(interceptors)
                .withOption(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                .keepAliveTimeout(60, TimeUnit.SECONDS)
                .maxInboundMessageSize(MAX_INBOUND)
                .useTransportSecurity();
        if (trustRussianCa) {
            builder.sslContext(sslContext());
        }
        return builder.build();
    }

    /** Per-RPC deadline for unary market-data calls only. */
    private static final class UnaryDeadlineInterceptor implements ClientInterceptor {
        private final long timeoutMs;

        private UnaryDeadlineInterceptor(Duration timeout) {
            this.timeoutMs = Math.max(500L, timeout.toMillis());
        }

        @Override
        public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
                MethodDescriptor<ReqT, RespT> method,
                CallOptions callOptions,
                Channel next
        ) {
            // Always clamp — SDK often sets a long default deadline that would ignore ours.
            return next.newCall(method, callOptions.withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS));
        }
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
