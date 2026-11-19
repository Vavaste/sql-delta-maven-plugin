package com.example.zoo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

// No web server: the application applies the Flyway migrations, validates the entities and exits.
@SpringBootApplication
public class ZooApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZooApplication.class, args);
	}
}
