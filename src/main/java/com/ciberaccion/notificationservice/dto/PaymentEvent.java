package com.ciberaccion.notificationservice.dto;

import lombok.*;
import java.math.BigDecimal;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
public class PaymentEvent {
    private Long paymentId;
    private String merchant;
    private BigDecimal amount;
    private String currency;
    private String status;
}