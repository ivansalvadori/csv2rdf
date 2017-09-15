package br.ufsc.inf.lapesd.csv2rdf;

import java.io.IOException;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan("br.ufsc.inf.lapesd.csv2rdf")
public class Main {

    public static void main(String[] args) throws IOException {
        SpringApplication.run(Main.class, args);
        CsvReader reader = new CsvReader();
        reader.process();
    }
}