package com.cropscanner;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Entry point for the Crop Disease Scanner, Diagnosis and Emergency
 * Escalation System.
 *
 * The application wires together:
 *  - a REST API for submitting scans (image + GPS coordinates),
 *  - a delegate call to an external ML inference microservice that performs
 *    the actual disease classification and lesion-severity estimation,
 *  - a remedy knowledge base,
 *  - a nearest-authority lookup backed by a real, importable KVK/agriculture
 *    extension office directory, and
 *  - an asynchronous webhook notifier that alerts the responsible authority
 *    when a scan is classified as severe.
 */
@SpringBootApplication
@EnableAsync
public class CropScannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CropScannerApplication.class, args);
    }

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}