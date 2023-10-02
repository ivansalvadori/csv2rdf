package br.ufsc.inf.lapesd.csv2rdf.controller;

import br.ufsc.inf.lapesd.csv2rdf.CsvReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
public class ConversorController {

        @Autowired
        private CsvReader csvReader;

        @GetMapping("/converte")
        public ResponseEntity<Resource> converteCsv() {
            InputStreamResource input = csvReader.process();

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .body(input);
        }
}
