package com.mightyoung.softwarefactory;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;
import org.springframework.context.annotation.Bean;
import java.time.Clock;

@SpringBootApplication
@Modulithic
public class SoftwareFactoryApplication {

    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    public static void main(String[] args) {
        SpringApplication.run(SoftwareFactoryApplication.class, args);
    }
}
