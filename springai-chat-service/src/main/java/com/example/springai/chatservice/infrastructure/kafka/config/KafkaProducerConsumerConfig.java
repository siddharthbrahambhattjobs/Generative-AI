package com.example.springai.chatservice.infrastructure.kafka.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaProducerConsumerConfig {

    @Bean
    KafkaAdmin.NewTopics springAiTopics() {
        return new KafkaAdmin.NewTopics(
                new NewTopic(KafkaTopics.ATTACHMENT_INGESTION_REQUESTED, 3, (short) 1),
                new NewTopic(KafkaTopics.ATTACHMENT_INGESTION_REQUESTED + "-dlt", 3, (short) 1),
                new NewTopic(KafkaTopics.USER_MEMORY_REFRESH_REQUESTED, 3, (short) 1),
                new NewTopic(KafkaTopics.USER_MEMORY_REFRESH_REQUESTED + "-dlt", 3, (short) 1)
        );
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
            ConsumerFactory<String, Object> consumerFactory) {
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setObservationEnabled(true);
        return factory;
    }

    @Bean
    KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        KafkaTemplate<String, Object> kafkaTemplate = new KafkaTemplate<>(producerFactory);
        kafkaTemplate.setObservationEnabled(true);
        return kafkaTemplate;
    }
}
