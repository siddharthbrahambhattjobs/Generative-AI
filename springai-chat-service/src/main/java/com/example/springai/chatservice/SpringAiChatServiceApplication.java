package com.example.springai.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import com.example.springai.chatservice.config.properties.JwtProperties;

@SpringBootApplication
@EnableConfigurationProperties(JwtProperties.class)
public class SpringAiChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringAiChatServiceApplication.class, args);
    }
}
