package com.epp.backend;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.epp.backend.mapper")
public class EppBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(EppBackendApplication.class, args);
    }
}
