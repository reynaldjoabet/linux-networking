package config;

import java.util.Properties;

class Hello {
	public static void main(String[] args) {

		Properties properties = System.getProperties();
		properties.forEach((key, value) -> System.out.println(key + ": " + value));
	}

	public static void printEnvVariables() {
		System.getenv().forEach((key, value) -> System.out.println(key + ": " + value));
	}
}