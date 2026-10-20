package com.hypeman;

import org.springframework.boot.SpringApplication;

public class TestHypemanApplication {

    public static void main(String[] args) {
        SpringApplication.from(HypemanApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
