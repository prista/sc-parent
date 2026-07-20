package com.drm.sandbox.manager_app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan("com.drm.sandbox")
public class ManagerAppApplication {

	public static void main(String[] args) {
		SpringApplication.run(ManagerAppApplication.class, args);
	}

}
