package com.tms.report.modules.grpc.config;

import com.tms.report.grpc.b2b.BusinessServiceGrpc;
import com.tms.report.grpc.config.ConfigServiceGrpc;
import com.tms.report.grpc.dispute.DisputeServiceGrpc;
import com.tms.report.grpc.kyc.KycServiceGrpc;
import com.tms.report.grpc.ledger.LedgerServiceGrpc;
import com.tms.report.grpc.notification.NotificationServiceGrpc;
import com.tms.report.grpc.settlement.SettlementServiceGrpc;
import com.tms.report.grpc.transaction.TransactionServiceGrpc;
import com.tms.report.grpc.user.UserServiceGrpc;
import com.tms.report.grpc.wallet.WalletServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GrpcConfig {

    private final List<ManagedChannel> channels = new ArrayList<>();

    private static final int MAX_MESSAGE_SIZE = 50 * 1024 * 1024; // 50MB for large CSV uploads

    private ManagedChannel createChannel(ServiceEndpoint endpoint) {
        ManagedChannel channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext()
                .maxInboundMessageSize(MAX_MESSAGE_SIZE).build();
        channels.add(channel);
        return channel;
    }

    @Bean
    public UserServiceGrpc.UserServiceBlockingStub userServiceStub(GrpcProperties props) {
        return UserServiceGrpc.newBlockingStub(createChannel(props.getService("user")));
    }

    @Bean
    public WalletServiceGrpc.WalletServiceBlockingStub walletServiceStub(GrpcProperties props) {
        return WalletServiceGrpc.newBlockingStub(createChannel(props.getService("wallet")));
    }

    @Bean
    public ConfigServiceGrpc.ConfigServiceBlockingStub configServiceStub(GrpcProperties props) {
        return ConfigServiceGrpc.newBlockingStub(createChannel(props.getService("config")));
    }

    @Bean
    public TransactionServiceGrpc.TransactionServiceBlockingStub transactionServiceStub(GrpcProperties props) {
        return TransactionServiceGrpc.newBlockingStub(createChannel(props.getService("transaction")));
    }

    @Bean
    public NotificationServiceGrpc.NotificationServiceBlockingStub notificationServiceStub(GrpcProperties props) {
        return NotificationServiceGrpc.newBlockingStub(createChannel(props.getService("notification")));
    }

    @Bean
    public LedgerServiceGrpc.LedgerServiceBlockingStub ledgerServiceStub(GrpcProperties props) {
        return LedgerServiceGrpc.newBlockingStub(createChannel(props.getService("ledger")));
    }

    @Bean
    public SettlementServiceGrpc.SettlementServiceBlockingStub settlementServiceStub(GrpcProperties props) {
        return SettlementServiceGrpc.newBlockingStub(createChannel(props.getService("settlement")));
    }

    @Bean
    public KycServiceGrpc.KycServiceBlockingStub kycServiceStub(GrpcProperties props) {
        return KycServiceGrpc.newBlockingStub(createChannel(props.getService("kyc")));
    }

    @Bean
    public DisputeServiceGrpc.DisputeServiceBlockingStub disputeServiceStub(GrpcProperties props) {
        return DisputeServiceGrpc.newBlockingStub(createChannel(props.getService("dispute")));
    }

    @Bean
    public BusinessServiceGrpc.BusinessServiceBlockingStub b2bServiceStub(GrpcProperties props) {
        return BusinessServiceGrpc.newBlockingStub(createChannel(props.getService("b2b")));
    }

    @PreDestroy
    public void shutdown() {
        channels.forEach(ch -> {
            try {
                ch.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                ch.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
    }
}
