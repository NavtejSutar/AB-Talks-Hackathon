package com.wren.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WrenAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(WrenAgentApplication.class, args);
    }
}
