package com.mightyoung.softwarefactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@SpringBootApplication
@Modulithic
public class SoftwareFactoryApplication {

    public static void main(String[] args) {
        SpringApplication.run(SoftwareFactoryApplication.class, args);
    }
}
