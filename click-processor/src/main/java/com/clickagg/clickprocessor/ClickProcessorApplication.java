package com.clickagg.clickprocessor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ClickProcessorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClickProcessorApplication.class, args);
    }
}
