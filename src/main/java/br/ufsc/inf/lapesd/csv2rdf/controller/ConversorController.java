package br.ufsc.inf.lapesd.csv2rdf.controller;

import br.ufsc.inf.lapesd.csv2rdf.CsvReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class ConversorController {

        @Autowired
        private CsvReader csvReader;

        @GetMapping("/converte")
        public ResponseEntity<String> converteCsv() {
            csvReader.process();

            return ResponseEntity.ok("Ve se funciona");
        }
}
