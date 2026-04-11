package com.ciberaccion.notificationservice.service;

import com.ciberaccion.notificationservice.dto.PaymentEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.List;

@Service
@Slf4j
@EnableScheduling
public class NotificationConsumer {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${aws.sqs.queue.url}")
    private String queueUrl;

    public NotificationConsumer(SqsClient sqsClient, ObjectMapper objectMapper) {
        this.sqsClient = sqsClient;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelay = 5000) // cada 5 segundos
    public void consume() {
        try {
            ReceiveMessageRequest receiveRequest = ReceiveMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .maxNumberOfMessages(10)
                    .waitTimeSeconds(5) // long polling
                    .build();

            List<Message> messages = sqsClient.receiveMessage(receiveRequest).messages();

            for (Message message : messages) {
                try {
                    PaymentEvent event = objectMapper.readValue(message.body(), PaymentEvent.class);
                    sendNotification(event);

                    // elimina el mensaje de la cola
                    sqsClient.deleteMessage(DeleteMessageRequest.builder()
                            .queueUrl(queueUrl)
                            .receiptHandle(message.receiptHandle())
                            .build());

                } catch (Exception e) {
                    log.error("Failed to process message: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Could not connect to SQS, retrying in 5s: {}", e.getMessage());
        }
    }

    private void sendNotification(PaymentEvent event) {
        log.info("📧 NOTIFICATION — paymentId={} merchant={} amount={} {} status={}",
                event.getPaymentId(),
                event.getMerchant(),
                event.getAmount(),
                event.getCurrency(),
                event.getStatus());
    }
}