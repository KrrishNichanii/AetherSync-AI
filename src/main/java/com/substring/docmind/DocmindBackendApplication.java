package com.substring.docmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;


/** AetherSync AI backend (Java package name kept for compatibility). */
@SpringBootApplication
@EnableScheduling
public class DocmindBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(DocmindBackendApplication.class, args);
	}

}
