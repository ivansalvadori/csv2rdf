package br.ufsc.inf.lapesd.csv2rdf.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthCheckController {
    @GetMapping("/health")
    public String checkHealth() {
        return "Servidor on";
    }
}
