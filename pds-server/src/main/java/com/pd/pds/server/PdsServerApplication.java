package com.pd.pds.server;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.pd.pds.server.mapper")
public class PdsServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(PdsServerApplication.class, args);
    }
}
