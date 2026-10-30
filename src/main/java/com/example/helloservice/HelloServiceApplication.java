package com.example.helloservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.example.helloservice", "com.leon.workflow"})
@org.springframework.boot.persistence.autoconfigure.EntityScan("com.leon.workflow.dao.entity")
@org.springframework.data.jpa.repository.config.EnableJpaRepositories("com.leon.workflow.dao.repository")
public class HelloServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(HelloServiceApplication.class, args);
    }
}
