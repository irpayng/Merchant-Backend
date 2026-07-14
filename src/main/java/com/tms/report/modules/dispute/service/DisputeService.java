package com.tms.report.modules.dispute.service;

import com.tms.report.core.security.MerchantScope;
import com.tms.report.modules.dispute.dto.CreateDisputeDto;
import com.tms.report.modules.grpc.service.GrpcClient;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DisputeService {

    private final GrpcClient grpcClient;
    private final MerchantScope merchantScope;

    public Map<String, Object> create(CreateDisputeDto dto) {
        Long merchantId = merchantScope.merchantId();
        if (merchantId == null) {
            throw new IllegalStateException("No authenticated merchant");
        }
        return grpcClient.createDispute(merchantId, dto.getTransactionReference(), dto.getSubject(), dto.getMessage());
    }
}
